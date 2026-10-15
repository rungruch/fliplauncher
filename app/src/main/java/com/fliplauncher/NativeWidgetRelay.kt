package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Only the service or the cover activity may register host 1024's update callback. */
object NativeRelayRuntime {
    private val covers = mutableSetOf<WidgetHelper>()
    @Volatile var service: NativeWidgetRelayService? = null
        private set
    val coverActive get() = covers.isNotEmpty()

    fun coverStarted(owner: WidgetHelper) {
        covers.add(owner)
        service?.pauseListening()
    }

    fun coverStopped(owner: WidgetHelper) {
        covers.remove(owner)
        if (!coverActive) boardChanged()
    }

    fun attach(service: NativeWidgetRelayService) { this.service = service }
    fun detach(service: NativeWidgetRelayService) { if (this.service === service) this.service = null }
    fun boardChanged() {
        runCatching { service?.syncBindings() }
            .onFailure { Log.w("FlipLauncherNative", "Cannot refresh native subscriptions", it) }
    }
}

/** In-memory provider payloads. Android's widget host retains the published native list. */
object NativeWidgetRelay {
    data class Entry(val provider: String, val layout: Int, val source: RemoteViews?, val reason: Int,
        val lenient: Boolean = false)
    private data class Result(val reason: Int, val lenient: Boolean = false)
    private val entries = mutableMapOf<Int, Entry>()
    private val sources = mutableMapOf<Int, RemoteViews>()
    private val handler = Handler(Looper.getMainLooper())
    private data class Update(val context: Context, val provider: String, val source: RemoteViews)
    private val updates = WidgetUpdateQueue<Update>(handler) { id, update, publish ->
        runCatching {
            val app = update.context.applicationContext
            val info = AppWidgetManager.getInstance(update.context).getAppWidgetInfo(id)
            if (id in WidgetScreenStore.ids(app) && info?.provider?.flattenToString() == update.provider) {
                // Native inflation stays on the UI thread, after burst coalescing, once per queue tick.
                val result = if (update.source.layoutId == 0) Result(R.string.native_widget_unsupported)
                    else classify(update.context, update.source)
                if (publish {
                    synchronized(entries) {
                        entries[id] = Entry(update.provider, update.source.layoutId,
                            if (result.reason == 0) update.source else null, result.reason, result.lenient)
                    }
                }) scheduleRefresh(app)
            }
        }.onFailure { Log.w(TAG, "Cannot process native widget $id", it) }
    }
    private var scheduled: Runnable? = null
    private const val TAG = "FlipLauncherNative"

    fun capture(context: Context, id: Int, info: AppWidgetProviderInfo, source: RemoteViews?) {
        // A null payload is the temporary provider default, not a new valid widget update.
        if (source == null || id !in WidgetScreenStore.ids(context)) return
        val copy = RemoteViews(source)
        synchronized(entries) { sources[id] = copy }
        // Replacement keeps the original deadline and rechecks every payload's nested layouts.
        updates.submit(id, Update(NativeWidgetSizing.context(context), info.provider.flattenToString(), copy))
    }

    private fun classify(context: Context, source: RemoteViews): Result {
        // A native, unattached context avoids AppCompat and does not attach provider adapters
        // to the outer FlipLauncher ID. Nested collections cannot preserve provider identity.
        val failure = try {
            return result(RemoteViews(source).apply(context, FrameLayout(context)), lenient = false)
        } catch (failure: RuntimeException) { failure }
        // A parent resolves the provider root's layout_* values in its own (our) resources, while
        // AppWidgetHostView uses the provider's. A provider @dimen size then fails only here.
        // GridLayout defaults unresolved sizes, so the row hosts such payloads in one instead.
        return try {
            result(RemoteViews(source).apply(context, GridLayout(context)), lenient = true)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Cannot relay ${source.`package`} layout ${source.layoutId}", failure)
            Result(R.string.native_widget_render_failed)
        }
    }

    private fun result(view: View, lenient: Boolean) =
        Result(if (hasCollection(view)) R.string.native_widget_collection else 0, lenient)

    private fun hasCollection(view: View): Boolean {
        if (view is AdapterView<*>) return true
        return view is ViewGroup && (0 until view.childCount).any { hasCollection(view.getChildAt(it)) }
    }

    fun entry(id: Int, provider: String): Entry? = synchronized(entries) {
        entries[id]?.takeIf { it.provider == provider }
    }

    fun forget(context: Context, id: Int) {
        updates.cancel(id)
        synchronized(entries) { entries.remove(id); sources.remove(id) }
        scheduleRefresh(context)
    }

    fun retain(ids: Set<Int>) {
        updates.retain(ids)
        synchronized(entries) { entries.keys.retainAll(ids); sources.keys.retainAll(ids) }
    }

    fun reclassify(context: Context) {
        val saved = WidgetScreenStore.ids(context).toSet()
        retain(saved)
        val latest = synchronized(entries) { sources.toMap() }
        val manager = AppWidgetManager.getInstance(context)
        latest.forEach { (id, source) ->
            manager.getAppWidgetInfo(id)?.let { capture(context, id, it, source) }
        }
    }

    fun scheduleRefresh(context: Context) {
        val app = context.applicationContext
        if (FlipLauncherWidgetProvider.widgetIds(app).isEmpty()) return
        // Coalesce rapid provider updates without continually postponing a busy widget.
        if (scheduled != null) return
        scheduled = Runnable {
            scheduled = null
            FlipLauncherWidgetProvider.refresh(app)
        }.also { handler.postDelayed(it, 500) }
    }
}

object NativeWidgetSizing {
    data class Surface(val width: Int, val height: Int)

    fun context(context: Context): Context {
        val app = context.applicationContext
        val display = DisplayHelper.findCoverDisplay(context)
        return if (display != null) app.createDisplayContext(display) else app
    }

    fun surface(context: Context, outerId: Int? = null): Surface {
        val manager = AppWidgetManager.getInstance(context)
        val ids = if (outerId != null) intArrayOf(outerId) else FlipLauncherWidgetProvider.widgetIds(context)
        val options = ids.map { manager.getAppWidgetOptions(it) }
        var width = options.map { it.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 352) }
            .filter { it > 0 }.minOrNull() ?: 352
        var height = options.map { it.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 339) }
            .filter { it > 0 }.minOrNull() ?: 339
        DisplayHelper.findCoverDisplay(context)?.let { display ->
            val metrics = context.applicationContext.createDisplayContext(display).resources.displayMetrics
            // Host options can arrive after a display transition. Never use stale larger bounds.
            width = minOf(width, (metrics.widthPixels / metrics.density).toInt())
            height = minOf(height, (metrics.heightPixels / metrics.density).toInt())
        }
        // Relayed rows have no card padding. Width: outer padding (16). Height: outer padding (16)
        // and the camera-notch spacer (32). Values match our layouts.
        return Surface((width - 16).coerceIn(1, 700), (height - 48).coerceIn(1, 900))
    }

    fun height(context: Context, id: Int, info: AppWidgetProviderInfo, surface: Surface): Int {
        return layout(context, id, info, surface).dp(context.resources.displayMetrics.density).height.roundToInt()
    }

    internal fun layout(context: Context, id: Int, info: AppWidgetProviderInfo, surface: Surface): WidgetSizing.Layout {
        val density = context.resources.displayMetrics.density
        return WidgetSizing.calculate(info, WidgetScreenStore.size(context, id),
            (surface.width * density).roundToInt(), (surface.height * density).roundToInt(), density, scrolling = true)
    }

    fun update(context: Context, id: Int, info: AppWidgetProviderInfo, surface: Surface) {
        val manager = AppWidgetManager.getInstance(context)
        val layout = layout(context, id, info, surface)
        if (!layout.fits) return
        val size = layout.dp(context.resources.displayMetrics.density)
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        val current = manager.getAppWidgetOptions(id)
        if (current.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) == width &&
            current.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) == height &&
            current.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) == width &&
            current.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) == height &&
            current.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) == arrayListOf(size)) return
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
        })
    }
}
