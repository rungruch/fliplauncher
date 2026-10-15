package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Samsung binds our own collection service; each row relays one compatible payload. */
class NativeWidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class WidgetFactory(context: Context, private val outerId: Int) : RemoteViewsFactory {
        private val app = context.applicationContext
        private var context = NativeWidgetSizing.context(app)
        private var ids = emptyList<Int>()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() {
            context = NativeWidgetSizing.context(app)
            ids = if (FlipLauncherWidgetProvider.ownsWidget(context, outerId)) WidgetScreenStore.ids(context) else emptyList()
        }
        override fun onDestroy() { ids = emptyList() }
        override fun getCount() = ids.size
        override fun getItemId(position: Int) = ids.getOrNull(position)?.toLong() ?: -1L
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 1
        override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.item_native_widget).apply {
            setTextViewText(R.id.nativeWidgetLabel, context.getString(R.string.native_loading))
            setViewVisibility(R.id.nativeWidgetCanvas, View.GONE)
            setTextViewText(R.id.nativeWidgetStatus, context.getString(R.string.native_loading))
        }

        override fun getViewAt(position: Int): RemoteViews? {
            val id = ids.getOrNull(position) ?: return null
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)
            val label = info?.let { runCatching { it.loadLabel(context.packageManager) }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: WidgetScreenStore.label(context, id)
            val entry = info?.let { NativeWidgetRelay.entry(id, it.provider.flattenToString()) }
            val source = entry?.source
            val layout = info?.let { NativeWidgetSizing.layout(context, id, it, NativeWidgetSizing.surface(context, outerId)) }
            val row = RemoteViews(context.packageName, R.layout.item_native_widget)
            row.setTextViewText(R.id.nativeWidgetLabel, label)
            row.setContentDescription(R.id.nativeWidgetTapTarget,
                context.getString(R.string.native_open_widget, label))
            val open = Intent().putExtra(CoverLaunchRouterActivity.EXTRA_HOSTED_WIDGET_ID, id)
            row.setOnClickFillInIntent(R.id.nativeWidgetOpen, open)
            row.setOnClickFillInIntent(R.id.nativeWidgetLabel, open)
            row.setOnClickFillInIntent(R.id.nativeWidgetTapTarget, open)
            row.removeAllViews(R.id.nativeWidgetContent)
            row.removeAllViews(R.id.nativeWidgetContentLenient)
            if (source != null && layout?.fits == true) {
                row.setViewVisibility(R.id.nativeWidgetCanvas, View.VISIBLE)
                // Relayed widgets render bare on the wallpaper; the list divider keeps the gap.
                // The tap target over the canvas opens the app, so no header or hint is needed.
                row.setInt(R.id.nativeWidgetRow, "setBackgroundResource", 0)
                row.setViewPadding(R.id.nativeWidgetRow, 0, 0, 0, 0)
                row.setViewVisibility(R.id.nativeWidgetHeader, View.GONE)
                row.setViewVisibility(R.id.nativeWidgetStatus, View.GONE)
                val size = layout.dp(context.resources.displayMetrics.density)
                row.setViewLayoutWidth(R.id.nativeWidgetCanvas, size.width, TypedValue.COMPLEX_UNIT_DIP)
                row.setViewLayoutHeight(R.id.nativeWidgetCanvas, size.height, TypedValue.COMPLEX_UNIT_DIP)
                // Add last: provider resource IDs may overlap this application's IDs.
                // Collection children cannot keep direct provider PendingIntent actions, so the
                // transparent tap target opens the widget provider's app through our brief router.
                // Lenient payloads need a parent that tolerates the provider's own layout_* resources.
                row.addView(if (entry.lenient) R.id.nativeWidgetContentLenient else R.id.nativeWidgetContent,
                    RemoteViews(source))
            } else {
                row.setViewVisibility(R.id.nativeWidgetCanvas, View.GONE)
                val reason = when {
                    info == null -> R.string.widget_unavailable_detail
                    layout?.fits == false -> R.string.native_widget_too_large
                    entry != null -> entry.reason
                    NativeWidgetRelayService.paused(context) -> R.string.native_paused
                    else -> R.string.native_waiting
                }
                row.setTextViewText(R.id.nativeWidgetStatus, context.getString(reason))
                row.setOnClickFillInIntent(R.id.nativeWidgetStatus, open)
            }
            return row
        }
    }
}
