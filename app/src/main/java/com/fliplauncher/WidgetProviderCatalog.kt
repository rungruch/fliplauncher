package com.fliplauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

data class WidgetProviderEntry(val info: AppWidgetProviderInfo, val label: String, val appLabel: String)

object WidgetProviderCatalog {
    fun load(context: Context): List<WidgetProviderEntry> = AppWidgetManager.getInstance(context)
        .getInstalledProvidersForProfile(Process.myUserHandle()).asSequence()
        .filter { it.provider.packageName != context.packageName }
        .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        .filter { it.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0 }
        .mapNotNull { info -> runCatching {
            WidgetProviderEntry(info, info.loadLabel(context.packageManager),
                info.activityInfo.applicationInfo.loadLabel(context.packageManager).toString())
        }.getOrNull() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appLabel + " " + it.label })
        .toList()
}

/** Loads static provider previews off the UI thread; the editor never starts a second live host. */
class WidgetPreviewLoader(private val activity: Activity) {
    @Volatile private var generation = 0
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Drawable>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Drawable) =
            (value as? BitmapDrawable)?.bitmap?.allocationByteCount ?: 64 * 1024
    }

    fun load(info: AppWidgetProviderInfo?, target: ImageView) {
        val requestGeneration = generation
        val key = info?.provider?.flattenToString()?.let { "$requestGeneration:$it:${activity.resources.displayMetrics.densityDpi}" }
        target.tag = key
        target.setImageResource(R.drawable.ic_widget_mark)
        if (info == null || key == null || executor.isShutdown) return
        cache.get(key)?.let { target.setImageDrawable(it); return }
        executor.execute {
            val drawable = runCatching {
                info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi)
                    ?: info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
            }.getOrNull()
            if (drawable != null && generation == requestGeneration) cache.put(key, drawable)
            target.post {
                if (!activity.isDestroyed && !activity.isFinishing && generation == requestGeneration && target.tag == key && drawable != null)
                    target.setImageDrawable(drawable)
            }
        }
    }

    fun clear(target: ImageView) { target.tag = null; target.setImageDrawable(null) }
    fun invalidate() { ++generation; cache.evictAll() }
    fun close() { invalidate(); executor.shutdownNow() }
}
