package com.fliplauncher

import android.app.Activity
import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast

/** Validate collection fill-in extras before launching a selected activity on the cover. */
class CoverLaunchRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cover tap puts us in the foreground, which always permits starting updates.
        NativeWidgetRelayService.autoStart(this)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID)
        if (intent.hasExtra(EXTRA_PINNED_COMPONENT)) {
            if (FlipLauncherWidgetProvider.ownsWidget(this, widgetId))
                launchShortcut(this, intent.getStringExtra(EXTRA_PINNED_COMPONENT).orEmpty())
            else Toast.makeText(this, R.string.invalid_selection, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (intent.hasExtra(EXTRA_HOSTED_WIDGET_ID)) {
            launchWidgetApp(widgetId, intent.getIntExtra(EXTRA_HOSTED_WIDGET_ID, -1))
            finish()
            return
        }
        val component = intent.getStringExtra(EXTRA_COMPONENT)
        Log.i("FlipLauncherLaunch", "Relay entered on display ${DisplayHelper.attachedDisplayId(this)}, widget $widgetId")
        if (!FlipLauncherWidgetProvider.ownsWidget(this, widgetId) || component == null ||
            component !in WidgetSelectionStore.load(this, widgetId).components) {
            Toast.makeText(this, R.string.invalid_selection, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = AppListLoader.resolve(this, component)
        if (app == null) {
            Toast.makeText(this, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            FlipLauncherWidgetProvider.refresh(this, intArrayOf(widgetId))
        } else {
            launchOnCover(this, app)
        }
        finish()
    }

    private fun launchWidgetApp(outerId: Int, hostedId: Int) {
        // Resolve from the actual binding rather than accepting an arbitrary package in extras.
        if (!FlipLauncherWidgetProvider.ownsWidget(this, outerId) || hostedId !in WidgetScreenStore.ids(this)) {
            Toast.makeText(this, R.string.invalid_selection, Toast.LENGTH_SHORT).show()
            return
        }
        launchHostedWidgetApp(this, hostedId)
    }

    companion object {
        const val EXTRA_COMPONENT = "com.fliplauncher.SELECTED_COMPONENT"
        const val EXTRA_HOSTED_WIDGET_ID = "com.fliplauncher.OPEN_HOSTED_WIDGET_APP"
        const val EXTRA_PINNED_COMPONENT = "com.fliplauncher.PINNED_COMPONENT"

        /** Dashboard fallback taps use the same binding, profile and cover-policy validation. */
        fun launchHostedWidgetApp(activity: Activity, hostedId: Int) {
            if (hostedId !in WidgetScreenStore.ids(activity)) {
                Toast.makeText(activity, R.string.invalid_selection, Toast.LENGTH_SHORT).show()
                return
            }
            val app = runCatching {
                val info = AppWidgetManager.getInstance(activity).getAppWidgetInfo(hostedId) ?: return@runCatching null
                if (info.profile != Process.myUserHandle()) return@runCatching null
                AppListLoader.resolveForPackage(activity, info.provider.packageName)
            }.onFailure { Log.w("FlipLauncherLaunch", "Cannot resolve app for widget $hostedId", it) }.getOrNull()
            if (app == null) {
                CoverSetupStatusStore.report(activity, WidgetScreenStore.label(activity, hostedId), CoverLaunchIssue.NO_LAUNCHER)
                Toast.makeText(activity, R.string.native_app_unavailable, Toast.LENGTH_LONG).show()
            } else launchOnCover(activity, app)
        }

        fun launchShortcut(activity: Activity, component: String) {
            val saved = AppShortcutStore.load(activity).firstOrNull { it.component == component }
            if (saved == null) {
                Toast.makeText(activity, R.string.invalid_selection, Toast.LENGTH_SHORT).show()
                return
            }
            val app = AppListLoader.resolve(activity, saved.component)
            if (app == null) {
                CoverSetupStatusStore.report(activity, saved.label, CoverLaunchIssue.UNAVAILABLE)
                Toast.makeText(activity, R.string.app_unavailable, Toast.LENGTH_LONG).show()
                FlipLauncherWidgetProvider.refresh(activity)
            } else launchOnCover(activity, app)
        }

        fun launchOnCover(activity: Activity, app: AppInfo) {
            if (!DisplayHelper.isOnCover(activity)) {
                CoverSetupStatusStore.report(activity, app.label, CoverLaunchIssue.WRONG_DISPLAY)
                Log.w("FlipLauncherLaunch", "Cover tap was delivered to display ${DisplayHelper.attachedDisplayId(activity)}")
                Toast.makeText(activity, R.string.cover_route_redirected, Toast.LENGTH_LONG).show()
                return
            }
            val cover = DisplayHelper.findCoverDisplay(activity)
            if (cover == null) {
                CoverSetupStatusStore.report(activity, app.label, CoverLaunchIssue.NO_COVER)
                Toast.makeText(activity, R.string.cover_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            val target = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
                component = app.component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            try {
                val manager = activity.getSystemService(ActivityManager::class.java)
                if (manager?.isActivityStartAllowedOnDisplay(activity, cover.displayId, target) != true) {
                    CoverSetupStatusStore.report(activity, app.label, CoverLaunchIssue.BLOCKED)
                    Log.w("FlipLauncherLaunch", "Display policy denied ${app.component} on ${cover.displayId}")
                    Toast.makeText(activity, R.string.cover_launch_blocked, Toast.LENGTH_LONG).show()
                    return
                }
                Log.i("FlipLauncherLaunch", "Launching ${app.component} on display ${cover.displayId}")
                activity.startActivity(target, DisplayHelper.activityOptions(cover.displayId))
                CoverSetupStatusStore.clearIssue(activity)
            } catch (failure: RuntimeException) {
                CoverSetupStatusStore.report(activity, app.label, CoverLaunchIssue.FAILED)
                Log.w("FlipLauncherLaunch", "Cover launch failed for ${app.component}", failure)
                Toast.makeText(activity, activity.getString(R.string.could_not_launch, app.label),
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
