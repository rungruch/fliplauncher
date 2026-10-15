package com.fliplauncher

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppShortcutIntegrationTest {
    private lateinit var context: Application
    private lateinit var manager: AppWidgetManager
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(Looper.getMainLooper()).idle()
        AppShortcutStore.preferences(context).edit().clear().commit()
        CoverSetupStatusStore.preferences(context).edit().clear().commit()
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        manager = AppWidgetManager.getInstance(context)
    }
    private fun bindOuter() {
        shadowOf(manager).addBoundWidget(100, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, FlipLauncherWidgetProvider::class.java)
        })
    }
    private fun route(component: String, outerId: Int = 100) = Intent(context, CoverLaunchRouterActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outerId)
        .putExtra(CoverLaunchRouterActivity.EXTRA_PINNED_COMPONENT, component)

    @Test fun nativeStripLaunchesAnExplicitImmutableRouteWithNoHostedWidgets() {
        bindOuter()
        val app = ShortcutTestApps.install(context, "one", "One")
        val component = ShortcutTestApps.component(app)
        AppShortcutStore.save(context, listOf(AppShortcut(component, "One")))
        val views = RemoteViews(context.packageName, R.layout.widget_native_cover)
        AppShortcutViews.bindNative(context, views, 100)
        val root = views.apply(context, FrameLayout(context))
        val strip = root.findViewById<LinearLayout>(R.id.appShortcutStrip)
        assertEquals(View.VISIBLE, strip.visibility)
        assertEquals(1, strip.childCount)
        assertEquals("One", strip.getChildAt(0).findViewById<TextView>(R.id.shortcutLabel).text.toString())
        strip.getChildAt(0).performClick()
        val launched = shadowOf(context).nextStartedActivity
        assertEquals(ComponentName(context, CoverLaunchRouterActivity::class.java), launched.component)
        assertEquals(component, launched.getStringExtra(CoverLaunchRouterActivity.EXTRA_PINNED_COMPONENT))
        assertEquals(100, launched.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        val pending = PendingIntent.getActivity(context, 100, launched, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(pending)
        assertTrue(pending.isImmutable)
        assertTrue(WidgetScreenStore.ids(context).isEmpty())
        assertFalse(NativeWidgetRelayService.running())
    }

    @Test fun staleRemovedShortcutAndUnknownWidgetCannotLaunch() {
        bindOuter()
        val app = ShortcutTestApps.install(context, "one", "One")
        val component = ShortcutTestApps.component(app)
        AppShortcutStore.save(context, listOf(AppShortcut(component, "One")))
        val unowned = Robolectric.buildActivity(CoverLaunchRouterActivity::class.java, route(component, 999)).create()
        assertTrue(unowned.get().isFinishing)
        unowned.destroy()
        AppShortcutStore.save(context, emptyList())
        val stale = Robolectric.buildActivity(CoverLaunchRouterActivity::class.java, route(component)).create()
        assertTrue(stale.get().isFinishing)
        stale.destroy()
        assertNull(shadowOf(context).nextStartedActivity)
        assertNull(CoverSetupStatusStore.lastIssue(context))
    }

    @Test fun unavailableSavedAppRecordsAnActionableIssueWithoutLaunching() {
        bindOuter()
        AppShortcutStore.save(context, listOf(AppShortcut("missing.app/.Main", "Missing")))
        val component = AppShortcutStore.load(context).single().component
        val controller = Robolectric.buildActivity(CoverLaunchRouterActivity::class.java, route(component)).create()
        assertEquals(LastCoverLaunchIssue("Missing", CoverLaunchIssue.UNAVAILABLE), CoverSetupStatusStore.lastIssue(context))
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, AppShortcutStore.load(context).size)
        controller.destroy()
    }

    @Test fun dashboardUsesTheSameShortcutsAndKeepsTheCoverOriginGate() {
        val app = ShortcutTestApps.install(context, "one", "One")
        AppShortcutStore.save(context, listOf(AppShortcut(ShortcutTestApps.component(app), "One")))
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val strip = LinearLayout(controller.get())
        AppShortcutViews.bindDashboard(controller.get(), strip)
        assertEquals(1, strip.childCount)
        strip.getChildAt(0).performClick()
        assertEquals(CoverLaunchIssue.WRONG_DISPLAY, CoverSetupStatusStore.lastIssue(context)?.issue)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.destroy()
    }

    @Test fun checklistReflectsNewSurfaceOnResumeAndDoesNotRequireUpdatesForApps() {
        val app = ShortcutTestApps.install(context, "one", "One")
        AppShortcutStore.save(context, listOf(AppShortcut(ShortcutTestApps.component(app), "One")))
        val controller = Robolectric.buildActivity(WidgetDashboardEditorActivity::class.java).create().start().resume()
        val editor = controller.get()
        assertEquals(editor.getString(R.string.setup_widget_missing), editor.findViewById<TextView>(R.id.setupWidgetStatus).text.toString())
        assertEquals(editor.getString(R.string.setup_content_ready, 1, 0), editor.findViewById<TextView>(R.id.setupContentStatus).text.toString())
        assertEquals(editor.getString(R.string.setup_updates_not_needed), editor.findViewById<TextView>(R.id.setupUpdatesStatus).text.toString())
        assertFalse(editor.findViewById<View>(R.id.nativeCoverUpdates).isEnabled)

        bindOuter()
        controller.pause().resume()
        assertEquals(editor.getString(R.string.setup_widget_added), editor.findViewById<TextView>(R.id.setupWidgetStatus).text.toString())
        assertFalse(NativeWidgetRelayService.running())

        CoverSetupStatusStore.report(context, "One", CoverLaunchIssue.BLOCKED)
        assertEquals(View.VISIBLE, editor.findViewById<View>(R.id.setupLaunchIssue).visibility)
        editor.findViewById<View>(R.id.dismissLaunchIssue).performClick()
        assertEquals(View.GONE, editor.findViewById<View>(R.id.setupLaunchIssue).visibility)
        controller.pause().stop().destroy()
    }
}
