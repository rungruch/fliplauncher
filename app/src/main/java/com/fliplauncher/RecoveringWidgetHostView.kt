package com.fliplauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import java.util.concurrent.Executor

/** Keep a failing provider attached for future reapply, but let the page show a safe fallback. */
class RecoveringWidgetHostView(context: Context,
    private val onPayload: (AppWidgetProviderInfo?, RemoteViews?) -> Unit = { _, _ -> }) :
    AppWidgetHostView(context) {
    var hasRenderFailure = false
        private set
    var onRenderStateChanged: (() -> Unit)? = null
    private var generation = 0
    private var released = false
    private var notification: Runnable? = null
    private var inflationExecutor: Executor? = null

    override fun setExecutor(executor: Executor?) {
        inflationExecutor = executor
        super.setExecutor(executor)
    }

    override fun updateAppWidget(views: RemoteViews?) {
        if (released) return
        val recovering = hasRenderFailure && views != null
        ++generation
        // A temporary default/null is not a new provider payload to retry.
        if (views != null) hasRenderFailure = false
        // Apply a recovery payload before exposing the old failing tree again. Normal updates
        // retain framework async inflation; switching executors cancels any older apply task.
        if (recovering) super.setExecutor(null)
        try { super.updateAppWidget(views) }
        catch (failure: RuntimeException) { failed(failure) }
        finally { if (recovering) super.setExecutor(inflationExecutor) }
        runCatching { onPayload(appWidgetInfo, views) }
            .onFailure { Log.w("FlipLauncherNative", "Cannot relay widget $appWidgetId", it) }
        notifyPage()
    }

    override fun getErrorView(): View {
        failed(null)
        // The page owns the accessible fallback and action. Keep the framework's error child safe.
        return TextView(context).apply { setText(R.string.widget_render_failed) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!hasRenderFailure) {
            try { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
            catch (failure: RuntimeException) { failed(failure) }
        }
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (hasRenderFailure) return
        try { super.onLayout(changed, left, top, right, bottom) }
        catch (failure: RuntimeException) { failed(failure) }
    }

    private fun failed(failure: RuntimeException?) {
        if (hasRenderFailure) return
        hasRenderFailure = true
        Log.w("FlipLauncherWidgets", "Widget $appWidgetId (${appWidgetInfo?.provider}) could not render", failure)
        notifyPage()
    }

    private fun notifyPage() {
        notification?.let(::removeCallbacks)
        val ticket = generation
        notification = Runnable {
            notification = null
            if (!released && ticket == generation) onRenderStateChanged?.invoke()
        }.also { post(it) }
    }

    fun release() {
        released = true
        ++generation
        notification?.let(::removeCallbacks)
        notification = null
        onRenderStateChanged = null
        setExecutor(null)
    }
}
