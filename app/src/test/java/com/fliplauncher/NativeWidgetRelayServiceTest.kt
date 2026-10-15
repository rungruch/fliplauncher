package com.fliplauncher

import android.app.Activity
import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeWidgetRelayServiceTest {
    private lateinit var context: Context
    private lateinit var manager: AppWidgetManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("native_widget_relay", Context.MODE_PRIVATE).edit().clear().commit()
        NativeWidgetRelay.retain(emptySet())
        val hosted = AppWidgetProviderInfo().apply {
            provider = ComponentName(context.packageName, "TestWidget")
            minWidth = 100; minHeight = 100
        }
        val outer = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, FlipLauncherWidgetProvider::class.java)
        }
        shadowOf(manager).addBoundWidget(7, hosted)
        shadowOf(manager).addBoundWidget(100, outer)
        WidgetScreenStore.saveIds(context, listOf(7))
        WidgetScreenStore.setSize(context, 7, WidgetSize.MEDIUM)
    }

    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    private fun host(owner: Any): AppWidgetHost = owner.javaClass.getDeclaredField("host").let {
        it.isAccessible = true; it.get(owner) as AppWidgetHost
    }

    private fun startedServices(): Int {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        var count = 0
        while (app.nextStartedService != null) count++
        return count
    }

    @Test fun autoStartRunsWithoutUserActionUntilPausedAndThrottlesRetries() {
        assertTrue(NativeWidgetRelayService.autoStart(context, now = 1_000_000L))
        assertEquals(1, startedServices())
        // A failed or blocked start must not loop through widget refreshes.
        assertFalse(NativeWidgetRelayService.autoStart(context, now = 1_000_000L + 1_000L))
        assertTrue(NativeWidgetRelayService.autoStart(context,
            now = 1_000_000L + NativeWidgetRelayService.AUTO_START_INTERVAL_MS))
        startedServices()
        NativeWidgetRelayService.stop(context)
        assertTrue(NativeWidgetRelayService.paused(context))
        assertFalse(NativeWidgetRelayService.autoStart(context, now = 9_000_000L))
        assertEquals(0, startedServices())
    }

    @Test fun stickyRestartHonoursPauseAndEditorStartClearsIt() {
        val controller = Robolectric.buildService(NativeWidgetRelayService::class.java).create()
        val service = controller.get()
        val start = { action: String? -> service.onStartCommand(
            action?.let { Intent(context, NativeWidgetRelayService::class.java).setAction(it) }, 0, 1) }
        try {
            assertEquals(Service.START_NOT_STICKY, start(NativeWidgetRelayService.ACTION_STOP))
            assertTrue(NativeWidgetRelayService.paused(context))
            assertEquals(Service.START_NOT_STICKY, start(null))
            assertEquals(Service.START_STICKY, start(NativeWidgetRelayService.ACTION_START))
            assertFalse(NativeWidgetRelayService.paused(context))
        } finally { controller.destroy() }
    }

    @Test fun autoStartNeedsHostedWidgets() {
        WidgetScreenStore.saveIds(context, emptyList())
        assertFalse(NativeWidgetRelayService.autoStart(context, now = 1_000_000L))
    }

    @Test fun coverAvailabilityReplacesContextWithoutDeletingSavedBindings() {
        val controller = Robolectric.buildService(NativeWidgetRelayService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(
                Intent(context, NativeWidgetRelayService::class.java).setAction(NativeWidgetRelayService.ACTION_START), 0, 1))
            val original = host(service)
            val cover = ShadowDisplayManager.addDisplay("w400dp-h400dp")
            advance()
            val onCover = host(service)
            assertNotSame(original, onCover)
            assertEquals(cover, shadowOf(onCover).context.display.displayId)
            ShadowDisplayManager.removeDisplay(cover)
            advance()
            assertNotSame(onCover, host(service))
            assertEquals(listOf(7), WidgetScreenStore.ids(context))
            assertEquals(WidgetSize.MEDIUM, WidgetScreenStore.size(context, 7))
            assertNotNull(manager.getAppWidgetInfo(7))
        } finally { controller.destroy() }
        assertNull(NativeRelayRuntime.service)
    }

    @Test fun displayRefreshWhileDashboardOwnsCallbackDoesNotStopDashboard() {
        val controller = Robolectric.buildService(NativeWidgetRelayService::class.java).create()
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val helper = WidgetHelper(activity.get())
        try {
            controller.get().onStartCommand(Intent(context, NativeWidgetRelayService::class.java)
                .setAction(NativeWidgetRelayService.ACTION_START), 0, 1)
            helper.onStart()
            val dashboard = host(helper)
            assertTrue(shadowOf(dashboard).isListening)
            ShadowDisplayManager.addDisplay("w400dp-h400dp")
            advance()
            assertTrue(NativeRelayRuntime.coverActive)
            assertTrue(shadowOf(dashboard).isListening)
            assertFalse(shadowOf(host(controller.get())).isListening)
            assertEquals(listOf(7), WidgetScreenStore.ids(context))
        } finally {
            helper.onDestroy()
            activity.destroy()
            controller.destroy()
        }
        assertFalse(NativeRelayRuntime.coverActive)
    }

    @Test fun dashboardContextRefreshReplacesHostAndKeepsItsOwnership() {
        val controller = Robolectric.buildService(NativeWidgetRelayService::class.java).create()
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val helper = WidgetHelper(activity.get())
        try {
            helper.onStart()
            val original = host(helper)
            helper.refreshDisplay()
            assertNotSame(original, host(helper))
            assertFalse(shadowOf(original).isListening)
            assertTrue(shadowOf(host(helper)).isListening)
            assertTrue(NativeRelayRuntime.coverActive)
            assertFalse(shadowOf(host(controller.get())).isListening)
            assertEquals(listOf(7), WidgetScreenStore.ids(context))
            assertNotNull(manager.getAppWidgetInfo(7))
        } finally {
            helper.onDestroy()
            activity.destroy()
            controller.destroy()
        }
    }
}
