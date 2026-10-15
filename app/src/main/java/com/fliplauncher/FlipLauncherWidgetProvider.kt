package com.fliplauncher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.widget.RemoteViews

/** Native cover content; cards open their provider app. Updates start on their own. */
class FlipLauncherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        NativeRelayRuntime.boardChanged()
        CoverSetupStatusStore.surfaceChanged(context)
        // Shared by every outer instance: resolve shortcuts and rasterize icons once per update.
        val shortcuts = AppShortcutStore.entries(context)
        val icons = AppShortcutViews.nativeIcons(context, shortcuts)
        appWidgetIds.filter { ownsWidget(context, it) }.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_native_cover)
            AppShortcutViews.bindNative(context, views, id, shortcuts, icons)
            views.setRemoteAdapter(R.id.nativeWidgetList, Intent(context, NativeWidgetListService::class.java).apply {
                data = Uri.parse("fliplauncher://native-list/$id")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            })
            views.setEmptyView(R.id.nativeWidgetList, R.id.nativeCoverEmpty)
            // Fill-in extras select the original hosted ID. The mutable template is explicit
            // and scoped to our activity; direct provider PendingIntents are not valid in rows.
            val rowTemplate = PendingIntent.getActivity(context, id, Intent(context, CoverLaunchRouterActivity::class.java).apply {
                data = Uri.parse("fliplauncher://native-widget-app/$id")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                DisplayHelper.activityOptions(DisplayHelper.FLIP5_COVER_DISPLAY_ID, forPendingIntent = true))
            views.setPendingIntentTemplate(R.id.nativeWidgetList, rowTemplate)
            val edit = PendingIntent.getActivity(context, id, editorIntent(context, id),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                DisplayHelper.activityOptions(Display.DEFAULT_DISPLAY, forPendingIntent = true))
            views.setOnClickPendingIntent(R.id.nativeCoverEmpty, edit)
            views.setTextViewText(R.id.nativeCoverEmpty, context.getString(
                if (shortcuts.isNotEmpty()) R.string.native_shortcuts_only else R.string.native_empty))
            runCatching {
                manager.updateAppWidget(id, views)
                manager.notifyAppWidgetViewDataChanged(id, R.id.nativeWidgetList)
            }.onFailure { android.util.Log.w("FlipLauncherNative", "Native cover update failed", it) }
        }
        NativeWidgetRelayService.autoStart(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NativeWidgetRelayService.ACTION_STOP -> NativeWidgetRelayService.stop(context)
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_FULLY_REMOVED, Intent.ACTION_BOOT_COMPLETED ->
                onUpdate(context, AppWidgetManager.getInstance(context), widgetIds(context))
            else -> super.onReceive(context, intent)
        }
    }
    // Removing the native surface keeps the user's hosted bindings for the dashboard.
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetSelectionStore.delete(context, it) }
        CoverSetupStatusStore.surfaceChanged(context)
        NativeRelayRuntime.boardChanged()
    }
    override fun onDisabled(context: Context) {
        CoverSetupStatusStore.surfaceChanged(context)
        NativeRelayRuntime.boardChanged()
    }
    companion object {
        fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, FlipLauncherWidgetProvider::class.java))
        fun ownsWidget(context: Context, id: Int): Boolean = id != AppWidgetManager.INVALID_APPWIDGET_ID &&
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, FlipLauncherWidgetProvider::class.java)
        fun refresh(context: Context, ids: IntArray = widgetIds(context)) {
            if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, FlipLauncherWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
        fun editorIntent(context: Context, id: Int) = Intent(context, WidgetDashboardEditorActivity::class.java).apply {
            data = Uri.parse("fliplauncher://widget-editor/$id")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
