package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences

enum class CoverLaunchIssue(val message: Int) {
    UNAVAILABLE(R.string.app_unavailable), NO_LAUNCHER(R.string.native_app_unavailable), WRONG_DISPLAY(R.string.cover_route_redirected),
    BLOCKED(R.string.cover_launch_blocked), NO_COVER(R.string.cover_unavailable), FAILED(R.string.shortcut_launch_failed)
}
data class LastCoverLaunchIssue(val label: String, val issue: CoverLaunchIssue)

object CoverSetupStatusStore {
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("fliplauncher_cover_setup", Context.MODE_PRIVATE)
    fun lastIssue(context: Context): LastCoverLaunchIssue? = runCatching {
        val prefs = preferences(context)
        val reason = prefs.getString("launch_issue", null) ?: return null
        LastCoverLaunchIssue(prefs.getString("launch_label", "").orEmpty(), CoverLaunchIssue.valueOf(reason))
    }.getOrNull()
    fun report(context: Context, label: CharSequence, issue: CoverLaunchIssue) {
        preferences(context).edit().putString("launch_label", label.toString())
            .putString("launch_issue", issue.name).apply()
    }
    fun clearIssue(context: Context) {
        preferences(context).edit().remove("launch_issue").remove("launch_label").apply()
    }
    /**
     * Change signal only: CoverSetupState.read queries live state and never reads this key. Writing a
     * fingerprint (rather than a counter) keeps frequent widget updates from re-rendering the editor
     * when the cover surface and update service did not actually change.
     */
    fun surfaceChanged(context: Context) {
        val fingerprint = "widget=${FlipLauncherWidgetProvider.widgetIds(context).isNotEmpty()};" +
            "updates=${NativeWidgetRelayService.running()}"
        preferences(context).edit().putString("surface_signal", fingerprint).apply()
    }
}

enum class CoverUpdatesState { NOT_NEEDED, NEEDS_SURFACE, STOPPED, RUNNING }

data class CoverSetupState(
    val widgetAdded: Boolean,
    val availableApps: Int,
    val availableWidgets: Int,
    val updatesRunning: Boolean,
    val setupPending: Boolean
) {
    val contentReady get() = availableApps + availableWidgets > 0
    val updatesState get() = when {
        updatesRunning -> CoverUpdatesState.RUNNING
        availableWidgets == 0 -> CoverUpdatesState.NOT_NEEDED
        !widgetAdded -> CoverUpdatesState.NEEDS_SURFACE
        else -> CoverUpdatesState.STOPPED
    }
    val canStartUpdates get() = widgetAdded && availableWidgets > 0 && !setupPending
    companion object {
        fun read(context: Context, setupQueued: Boolean = false,
            shortcuts: List<AppShortcutEntry> = AppShortcutStore.entries(context)): CoverSetupState {
            val manager = AppWidgetManager.getInstance(context)
            return CoverSetupState(
                widgetAdded = FlipLauncherWidgetProvider.widgetIds(context).isNotEmpty(),
                availableApps = shortcuts.count { it.app != null },
                availableWidgets = WidgetScreenStore.ids(context).count { manager.getAppWidgetInfo(it) != null },
                updatesRunning = NativeWidgetRelayService.running(),
                setupPending = setupQueued || WidgetScreenStore.pending(context) != null
            )
        }
    }
}
