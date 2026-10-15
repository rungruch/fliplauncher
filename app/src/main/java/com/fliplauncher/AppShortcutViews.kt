package com.fliplauncher

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap

object AppShortcutViews {
    /** Rasterize once per update; the bitmaps are shared by every outer widget instance. */
    fun nativeIcons(context: Context, entries: List<AppShortcutEntry>): List<Bitmap?> {
        val iconSize = (40 * context.resources.displayMetrics.density).toInt().coerceIn(40, 144)
        return entries.map { entry -> entry.app?.icon?.let { runCatching { it.toBitmap(iconSize, iconSize) }.getOrNull() } }
    }

    fun bindNative(context: Context, views: RemoteViews, outerId: Int,
        entries: List<AppShortcutEntry> = AppShortcutStore.entries(context),
        icons: List<Bitmap?> = nativeIcons(context, entries)) {
        views.removeAllViews(R.id.appShortcutStrip)
        views.setViewVisibility(R.id.appShortcutStrip, if (entries.isEmpty()) View.GONE else View.VISIBLE)
        entries.forEachIndexed { index, entry ->
            val tile = RemoteViews(context.packageName, R.layout.item_cover_shortcut)
            tile.setTextViewText(R.id.shortcutLabel, entry.label)
            tile.setContentDescription(R.id.shortcutTile, context.getString(
                if (entry.app == null) R.string.shortcut_unavailable_label else R.string.open_shortcut, entry.label))
            val icon = icons.getOrNull(index)
            if (icon == null) tile.setImageViewResource(R.id.shortcutIcon, R.drawable.ic_app_shortcut)
            else tile.setImageViewBitmap(R.id.shortcutIcon, icon)
            tile.setInt(R.id.shortcutIcon, "setImageAlpha", if (entry.app == null) 100 else 255)
            val target = Intent(context, CoverLaunchRouterActivity::class.java).apply {
                data = Uri.parse("fliplauncher://app-shortcut/$outerId/${Uri.encode(entry.shortcut.component)}")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outerId)
                putExtra(CoverLaunchRouterActivity.EXTRA_PINNED_COMPONENT, entry.shortcut.component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tile.setOnClickPendingIntent(R.id.shortcutTile, PendingIntent.getActivity(context, outerId, target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                DisplayHelper.activityOptions(DisplayHelper.FLIP5_COVER_DISPLAY_ID, forPendingIntent = true)))
            views.addView(R.id.appShortcutStrip, tile)
        }
    }

    /** Returns whether any shortcut tiles were bound. */
    fun bindDashboard(activity: Activity, strip: LinearLayout): Boolean {
        strip.removeAllViews()
        val entries = AppShortcutStore.entries(activity)
        strip.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        entries.forEach { entry ->
            val tile = LayoutInflater.from(activity).inflate(R.layout.item_cover_shortcut, strip, false)
            tile.findViewById<TextView>(R.id.shortcutLabel).text = entry.label
            tile.findViewById<ImageView>(R.id.shortcutIcon).apply {
                if (entry.app == null) setImageResource(R.drawable.ic_app_shortcut) else setImageDrawable(entry.app.icon)
                imageAlpha = if (entry.app == null) 100 else 255
            }
            tile.contentDescription = activity.getString(
                if (entry.app == null) R.string.shortcut_unavailable_label else R.string.open_shortcut, entry.label)
            tile.setOnClickListener { CoverLaunchRouterActivity.launchShortcut(activity, entry.shortcut.component) }
            strip.addView(tile)
        }
        return entries.isNotEmpty()
    }
}
