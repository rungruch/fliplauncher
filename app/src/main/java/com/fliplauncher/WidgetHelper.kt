package com.fliplauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.Advanceable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class HostedWidget(val id: Int, val info: AppWidgetProviderInfo?, val label: String, val size: WidgetSize)

/** Standard allocate -> bind -> configure flow. Closing an activity never deletes the host. */
class WidgetHelper(
    private val activity: Activity,
    private val owner: String = "cover",
    private val requestBind: ((Intent) -> Unit)? = null,
    private val onChanged: () -> Unit = {},
    private val onMessage: (Int) -> Unit = {}
) {
    private fun displayContext(): Context = runCatching { activity.display }.getOrNull()
        ?.let(activity::createDisplayContext) ?: activity
    private var manager = AppWidgetManager.getInstance(displayContext())
    private var host = createHost(displayContext())
    private fun createHost(context: Context) = object : AppWidgetHost(context, WidgetScreenStore.HOST_ID) {
        override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
            RecoveringWidgetHostView(context) { currentInfo, payload ->
                NativeWidgetRelay.capture(context, id, currentInfo ?: info, payload)
            }.apply { setExecutor(inflationExecutor()) }
        override fun onProviderChanged(id: Int, info: AppWidgetProviderInfo) {
            NativeWidgetRelay.forget(activity, id)
            super.onProviderChanged(id, info)
        }
        override fun onAppWidgetRemoved(appWidgetId: Int) {
            super.onAppWidgetRemoved(appWidgetId)
            (views.remove(appWidgetId) as? RecoveringWidgetHostView)?.release()
            NativeWidgetRelay.forget(activity, appWidgetId)
            if (appWidgetId in WidgetScreenStore.ids(activity)) {
                WidgetScreenStore.forget(activity, appWidgetId)
                publishChanges()
            }
        }
        fun releaseViews() = clearViews()
    }
    private val views = mutableMapOf<Int, AppWidgetHostView>()
    private var listening = false
    private var ownsCoverCallback = false
    private var executor: ThreadPoolExecutor? = null

    private fun inflationExecutor(): ThreadPoolExecutor = executor ?: ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(WidgetScreenStore.MAX_WIDGETS * 2),
        // Framework apply jobs must complete. A saturated pool falls back to the submitting thread.
        ThreadPoolExecutor.CallerRunsPolicy()
    ).also { it.allowCoreThreadTimeOut(true); executor = it }

    fun refreshDisplay() {
        val wasListening = listening
        if (wasListening) { runCatching { host.stopListening() }; listening = false }
        releaseViews()
        host.releaseViews()
        views.clear()
        val context = displayContext()
        manager = AppWidgetManager.getInstance(context)
        host = createHost(context)
        NativeWidgetRelay.reclassify(activity)
        if (wasListening) onStart()
    }

    fun screens(): List<HostedWidget> {
        val owned = runCatching { host.appWidgetIds.toSet() }.getOrNull()
        val saved = WidgetScreenStore.ids(activity)
        val remaining = if (owned == null) saved else saved.filter { it in owned }
        if (remaining != saved) WidgetScreenStore.saveIds(activity, remaining)
        return remaining.map { id ->
            val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
            val label = info?.let { runCatching { it.loadLabel(activity.packageManager) }.getOrNull() }
                ?.takeIf(String::isNotBlank) ?: WidgetScreenStore.label(activity, id)
            WidgetScreenStore.setLabel(activity, id, label)
            HostedWidget(id, info, label, WidgetScreenStore.size(activity, id))
        }
    }

    fun onStart() {
        if (listening) return
        NativeRelayRuntime.coverStarted(this)
        ownsCoverCallback = true
        runCatching { host.startListening(); listening = true }.onFailure {
            NativeRelayRuntime.coverStopped(this)
            ownsCoverCallback = false
            Log.w("FlipLauncherWidgets", "Cannot listen for widgets", it)
            onMessage(R.string.widgets_update_failed)
        }
    }

    fun onStop() {
        // A phone editor that never listened must not stop the cover instance's callbacks.
        if (listening) { runCatching { host.stopListening() }; listening = false }
        if (ownsCoverCallback) {
            ownsCoverCallback = false
            NativeRelayRuntime.coverStopped(this)
        }
    }

    fun onDestroy() {
        onStop()
        releaseViews()
        host.releaseViews()
        views.clear()
        executor?.shutdownNow()
        executor = null
    }

    fun createView(widget: HostedWidget): AppWidgetHostView? {
        val info = widget.info ?: return null
        return views.getOrPut(widget.id) { host.createView(activity, widget.id, info) }
    }

    private fun releaseViews() { views.values.forEach { (it as? RecoveringWidgetHostView)?.release() } }

    fun openApp(id: Int) = CoverLaunchRouterActivity.launchHostedWidgetApp(activity, id)

    fun advance(id: Int) {
        val view = views[id] ?: return
        if ((view as? RecoveringWidgetHostView)?.hasRenderFailure == true) return
        val targetId = view.appWidgetInfo?.autoAdvanceViewId ?: return
        if (targetId <= 0) return
        val target = view.findViewById<View>(targetId) as? Advanceable ?: return
        runCatching { target.fyiWillBeAdvancedByHostKThx(); target.advance() }
            .onFailure { Log.w("FlipLauncherWidgets", "Widget auto-advance failed", it) }
    }

    fun startAdd(info: AppWidgetProviderInfo) {
        if (WidgetScreenStore.pending(activity) != null) { onMessage(R.string.finish_pending_setup); return }
        if (screens().size >= WidgetScreenStore.MAX_WIDGETS) { onMessage(R.string.widget_screen_limit); return }
        if (info.provider.packageName == activity.packageName ||
            info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN == 0) return
        try {
            val id = host.allocateAppWidgetId()
            WidgetScreenStore.setPending(activity, PendingWidgetSetup(id, owner,
                info.provider.flattenToString(), false, BIND))
            val options = initialOptions(info)
            if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options)) {
                configureOrFinish()
            } else {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
                }
                checkNotNull(requestBind).invoke(intent)
            }
            onChanged()
        } catch (failure: RuntimeException) { failSetup(failure) }
    }

    fun onBindResult(resultCode: Int) {
        val pending = ownedPending() ?: return
        if (pending.stage != BIND) return
        // Many system activities return RESULT_OK with no data; our stored ID is authoritative.
        if (resultCode == Activity.RESULT_OK && boundInfo(pending) != null) configureOrFinish()
        else cancelPending()
    }

    fun onConfigureResult(resultCode: Int) {
        val pending = ownedPending() ?: return
        if (pending.stage != CONFIGURE) return
        if (resultCode == Activity.RESULT_OK && boundInfo(pending) != null) finishSetup()
        else cancelPending()
    }

    fun reconfigure(widget: HostedWidget) {
        if (WidgetScreenStore.pending(activity) != null) { onMessage(R.string.finish_pending_setup); return }
        val info = widget.info ?: return
        if (info.configure == null) return
        WidgetScreenStore.setPending(activity, PendingWidgetSetup(widget.id, owner,
            info.provider.flattenToString(), true, CONFIGURE_READY))
        onChanged()
    }

    private fun configureOrFinish() {
        val pending = ownedPending() ?: return
        val info = boundInfo(pending) ?: run { cancelPending(); return }
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        if (info.configure == null || optional) finishSetup()
        else {
            WidgetScreenStore.setPending(activity, pending.copy(stage = CONFIGURE_READY))
            onChanged()
        }
    }

    /** Called only when the editor has resumed and regained window focus after a result. */
    fun launchPendingConfiguration() {
        val pending = ownedPending() ?: return
        if (pending.stage != CONFIGURE_READY || !activity.hasWindowFocus() || activity.isFinishing) return
        WidgetScreenStore.setPending(activity, pending.copy(stage = CONFIGURE))
        try {
            // System-mediated sender supports provider configuration activities that are not exported.
            // No NEW_TASK flag: the provider must return its result to this editor.
            host.startAppWidgetConfigureActivityForResult(activity, pending.id, 0, CONFIGURE_REQUEST,
                DisplayHelper.widgetConfigurationOptions())
            onChanged()
        } catch (failure: RuntimeException) { failSetup(failure) }
    }

    private fun finishSetup() {
        val pending = ownedPending() ?: return
        val info = boundInfo(pending) ?: run { cancelPending(); return }
        WidgetScreenStore.completeSetup(activity, pending, info.loadLabel(activity.packageManager))
        publishChanges()
    }

    fun cancelPending(force: Boolean = false) {
        val pending = WidgetScreenStore.pending(activity) ?: return
        if (!force && pending.owner != owner) return
        if (!pending.existing) runCatching { host.deleteAppWidgetId(pending.id) }
        WidgetScreenStore.setPending(activity, null)
        publishChanges()
    }

    private fun failSetup(failure: RuntimeException) {
        Log.w("FlipLauncherWidgets", "Widget setup failed", failure)
        cancelPending()
        onMessage(R.string.widget_setup_failed)
    }

    private fun ownedPending() = WidgetScreenStore.pending(activity)?.takeIf { it.owner == owner }

    private fun boundInfo(pending: PendingWidgetSetup): AppWidgetProviderInfo? =
        manager.getAppWidgetInfo(pending.id)?.takeIf { it.provider.flattenToString() == pending.provider }

    fun remove(id: Int) {
        if (id !in WidgetScreenStore.ids(activity)) return
        try { host.deleteAppWidgetId(id) }
        catch (failure: RuntimeException) {
            Log.w("FlipLauncherWidgets", "Cannot remove widget", failure)
            onMessage(R.string.widget_remove_failed)
            return
        }
        (views.remove(id) as? RecoveringWidgetHostView)?.release()
        NativeWidgetRelay.forget(activity, id)
        WidgetScreenStore.forget(activity, id)
        publishChanges()
    }

    fun move(from: Int, to: Int, notify: Boolean = true) {
        val ids = WidgetScreenStore.ids(activity).toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        WidgetScreenStore.saveIds(activity, ids)
        if (notify) publishChanges()
    }

    fun setSize(widget: HostedWidget, size: WidgetSize) {
        if (widget.id !in WidgetScreenStore.ids(activity)) return
        if (size != WidgetSize.FULL && size != WidgetSize.AUTO &&
            widget.info?.resizeMode?.and(AppWidgetProviderInfo.RESIZE_VERTICAL) != AppWidgetProviderInfo.RESIZE_VERTICAL) return
        WidgetScreenStore.setSize(activity, widget.id, size)
        publishChanges()
    }

    fun publishChanges() {
        NativeRelayRuntime.boardChanged()
        FlipLauncherWidgetProvider.refresh(activity)
        onChanged()
    }

    private fun initialOptions(info: AppWidgetProviderInfo): Bundle {
        // Only initial hints: the cover host publishes measured dimensions when its page appears.
        // Do not tell providers they are smaller than their supported minimum before setup.
        val density = activity.resources.displayMetrics.density
        val width = maxOf(280, kotlin.math.ceil(info.minWidth / density).toInt())
        val height = maxOf(220, kotlin.math.ceil(info.minHeight / density).toInt())
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
        }
    }

    companion object {
        const val CONFIGURE_REQUEST = 5104
        private const val BIND = "bind"
        private const val CONFIGURE_READY = "configure_ready"
        private const val CONFIGURE = "configure"
    }
}
