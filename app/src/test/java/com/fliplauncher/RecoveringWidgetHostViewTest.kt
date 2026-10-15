package com.fliplauncher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAppWidgetHost
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.Executors
import java.time.Duration

/** Exercise real framework RemoteViews apply/reapply and async errors instead of its normal no-op shadow. */
@Implements(AppWidgetHostView::class)
class FrameworkWidgetHostViewShadow : ShadowViewGroup()

@Implements(AppWidgetHost::class)
class FactoryWidgetHostShadow : ShadowAppWidgetHost() {
    @RealObject private lateinit var real: AppWidgetHost
    @Implementation override fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        ReflectionHelpers.callInstanceMethod<AppWidgetHostView>(real, "onCreateView",
            ClassParameter.from(Context::class.java, context), ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(AppWidgetProviderInfo::class.java, info)).apply { setAppWidget(id, info) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FrameworkWidgetHostViewShadow::class, FactoryWidgetHostShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RecoveringWidgetHostViewTest {
    private class FailingChild(context: Context, val measureFailure: Boolean = false) : View(context) {
        var broken = true
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (broken && measureFailure) error("Provider measure failure")
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (broken && !measureFailure) error("Provider layout failure")
        }
    }
    private fun info(activity: Activity) = AppWidgetProviderInfo().apply {
        provider = ComponentName(activity.packageName, "RecoveryTestProvider")
        label = "Test widget"; minWidth = 40; minHeight = 40
        ReflectionHelpers.setField(this, "providerInfo", ActivityInfo().apply {
            packageName = activity.packageName; name = provider.className
            applicationInfo = activity.applicationInfo
        })
    }
    private fun measureAndLayout(view: View) {
        val spec = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, 300, 300)
    }

    @Test fun layoutFailureShowsOnlyThatPagesFallbackAndNewPayloadRecoversWithoutDeletingBinding() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val provider = info(activity)
        val manager = AppWidgetManager.getInstance(activity)
        shadowOf(manager).addBoundWidget(7, provider)
        shadowOf(manager).addBoundWidget(8, provider)
        WidgetScreenStore.saveIds(activity, listOf(7, 8))
        val helper = WidgetHelper(activity)
        try {
            val adapter = WidgetPageAdapter(helper)
            adapter.submit(listOf(HostedWidget(7, provider, "Test widget", WidgetSize.COMPACT),
                HostedWidget(8, provider, "Other widget", WidgetSize.COMPACT)))
            val parent = FrameLayout(activity)
            activity.setContentView(parent)
            val holder = adapter.createViewHolder(parent, 0)
            parent.addView(holder.itemView)
            adapter.bindViewHolder(holder, 0)
            val otherPage = adapter.createViewHolder(parent, 0)
            parent.addView(otherPage.itemView)
            adapter.bindViewHolder(otherPage, 1)
            val otherHost = otherPage.hostView as RecoveringWidgetHostView
            otherHost.setExecutor(null)
            otherHost.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            measureAndLayout(otherPage.itemView)
            val host = holder.hostView as RecoveringWidgetHostView
            host.setExecutor(null)
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            val root = host.getChildAt(0) as android.view.ViewGroup
            val broken = FailingChild(activity)
            root.addView(broken, android.view.ViewGroup.LayoutParams(100, 100))
            measureAndLayout(host)
            assertTrue(host.hasRenderFailure)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(View.VISIBLE, holder.fallback.visibility)
            assertEquals(View.GONE, host.visibility)
            assertEquals(listOf(7, 8), WidgetScreenStore.ids(activity))
            assertNotNull(manager.getAppWidgetInfo(7))
            assertFalse(otherHost.hasRenderFailure)
            assertEquals(View.VISIBLE, otherHost.visibility)
            assertEquals(View.GONE, otherPage.fallback.visibility)

            broken.broken = false
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            measureAndLayout(parent)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(host.hasRenderFailure)
            assertEquals(View.GONE, holder.fallback.visibility)
            assertEquals(View.VISIBLE, host.visibility)
            assertSame(host, helper.createView(HostedWidget(7, provider, "Test widget", WidgetSize.COMPACT)))
            assertNotNull(NativeWidgetRelay.entry(7, provider.provider.flattenToString())?.source)
        } finally { helper.onDestroy(); controller.pause().stop().destroy() }
    }

    @Test fun measureFailureIsContainedAndANewerUpdateSupersedesThePendingFailureNotice() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val host = RecoveringWidgetHostView(activity)
        val parent = FrameLayout(activity).apply { addView(host) }
        activity.setContentView(parent)
        try {
            host.setAppWidget(7, info(activity))
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            val broken = FailingChild(activity, measureFailure = true)
            (host.getChildAt(0) as android.view.ViewGroup).addView(broken, android.view.ViewGroup.LayoutParams(100, 100))
            val observed = mutableListOf<Boolean>()
            host.onRenderStateChanged = { observed += host.hasRenderFailure }
            measureAndLayout(host)
            assertTrue(host.hasRenderFailure)
            broken.broken = false
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(false), observed)
            measureAndLayout(host)
            assertFalse(host.hasRenderFailure)
            host.release()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun asynchronousInflationErrorCanRecoverAndReleaseCancelsPageNotifications() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val host = RecoveringWidgetHostView(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            host.setAppWidget(7, info(activity))
            host.setExecutor(executor)
            host.updateAppWidget(RemoteViews(activity.packageName, 0x7ffffffe))
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!host.hasRenderFailure) Thread.sleep(5)
            }
            assertTrue("Framework async error was not reported", host.hasRenderFailure)
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (host.findViewById<View>(R.id.shortcutWidgetCount) == null) Thread.sleep(5)
            }
            assertFalse(host.hasRenderFailure)
            assertNotNull(host.findViewById<View>(R.id.shortcutWidgetCount))
            var notified = false
            host.onRenderStateChanged = { notified = true }
            host.updateAppWidget(null)
            host.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(notified)
        } finally { executor.shutdownNow(); controller.pause().stop().destroy() }
    }

    @Test fun recoveryAppliesBeforeShowingTheOldFailingTreeAndRestoresAsyncInflation() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val host = RecoveringWidgetHostView(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        try {
            host.setAppWidget(7, info(activity))
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            (host.getChildAt(0) as android.view.ViewGroup).addView(FailingChild(activity),
                android.view.ViewGroup.LayoutParams(100, 100))
            measureAndLayout(host)
            assertTrue(host.hasRenderFailure)
            val queued = mutableListOf<Runnable>()
            host.setExecutor { queued += it }
            // A replacement root removes the failing child. A queued recovery would expose it
            // before the replacement applies and leave the host quarantined again.
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.item_cover_shortcut))
            assertTrue(queued.isEmpty())
            measureAndLayout(host)
            assertFalse(host.hasRenderFailure)
            host.updateAppWidget(RemoteViews(activity.packageName, R.layout.widget_dashboard_shortcut))
            assertEquals(1, queued.size)
            host.release()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fallbackLaunchRejectsRemovedWidgetsAndKeepsTheCoverOriginGate() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val app = ShortcutTestApps.install(activity, "recovery", "Recovery")
        val info = info(activity).apply { provider = ComponentName(app.activityInfo.packageName, "Widget") }
        shadowOf(AppWidgetManager.getInstance(activity)).addBoundWidget(7, info)
        try {
            WidgetScreenStore.saveIds(activity, listOf(7))
            CoverSetupStatusStore.clearIssue(activity)
            CoverLaunchRouterActivity.launchHostedWidgetApp(activity, 7)
            assertEquals(CoverLaunchIssue.WRONG_DISPLAY, CoverSetupStatusStore.lastIssue(activity)?.issue)
            CoverSetupStatusStore.clearIssue(activity)
            WidgetScreenStore.forget(activity, 7)
            CoverLaunchRouterActivity.launchHostedWidgetApp(activity, 7)
            assertNull(CoverSetupStatusStore.lastIssue(activity))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.destroy() }
    }

    @Test fun fallbackLaunchUsesAnotherMainEntryWhenTheProviderHidesItsLauncherIcon() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val pkg = "test.hidden.weather"
        fun entry(name: String, enabled: Boolean = true, permission: String? = null) =
            android.content.pm.ResolveInfo().apply {
                nonLocalizedLabel = "Weather"
                activityInfo = ActivityInfo().apply {
                    packageName = pkg; this.name = "$pkg.$name"; exported = true; this.enabled = enabled
                    this.permission = permission
                    applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; this.enabled = true }
                }
            }
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN).setPackage(pkg)
        val pm = shadowOf(activity.packageManager)
        pm.addResolveInfoForIntent(main, entry("Disabled", enabled = false))
        pm.addResolveInfoForIntent(main, entry("Guarded", permission = "test.PRIVATE"))
        pm.addResolveInfoForIntent(main, entry("Searchable"))
        val info = info(activity).apply { provider = ComponentName(pkg, "$pkg.Widget") }
        shadowOf(AppWidgetManager.getInstance(activity)).addBoundWidget(7, info)
        try {
            assertNull(activity.packageManager.getLaunchIntentForPackage(pkg))
            assertEquals(ComponentName(pkg, "$pkg.Searchable"), AppListLoader.resolveForPackage(activity, pkg)?.component)
            assertNull(AppListLoader.resolveForPackage(activity, "test.missing"))
            WidgetScreenStore.saveIds(activity, listOf(7))
            CoverSetupStatusStore.clearIssue(activity)
            // Resolution succeeds and reaches the cover-origin gate instead of reporting no launcher.
            CoverLaunchRouterActivity.launchHostedWidgetApp(activity, 7)
            assertEquals(CoverLaunchIssue.WRONG_DISPLAY, CoverSetupStatusStore.lastIssue(activity)?.issue)
        } finally { WidgetScreenStore.forget(activity, 7); controller.destroy() }
    }
}
