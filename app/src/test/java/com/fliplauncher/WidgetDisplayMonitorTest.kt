package com.fliplauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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
class WidgetDisplayMonitorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun configurationBurstTriggersOneRefresh() {
        var count = 0
        val monitor = WidgetDisplayMonitor(context, Handler(Looper.getMainLooper())) { count++ }
        monitor.configurationChanged(); advance(100)
        monitor.configurationChanged(); advance(100)
        assertEquals(0, count)
        advance(50)
        assertEquals(1, count)
        monitor.close()
    }

    @Test fun coverAdditionAndRemovalAreObservedAndUnrelatedDisplaysAreIgnored() {
        var count = 0
        val monitor = WidgetDisplayMonitor(context, Handler(Looper.getMainLooper())) { count++ }
        val cover = ShadowDisplayManager.addDisplay("w400dp-h400dp")
        assertEquals(DisplayHelper.FLIP5_COVER_DISPLAY_ID, cover)
        advance(500)
        assertEquals(1, count)
        ShadowDisplayManager.addDisplay("w800dp-h600dp")
        advance(500)
        assertEquals(1, count)
        ShadowDisplayManager.removeDisplay(cover)
        advance(500)
        assertEquals(2, count)
        monitor.close()
    }

    @Test fun closingCancelsPendingAndFutureRefreshes() {
        var count = 0
        val monitor = WidgetDisplayMonitor(context, Handler(Looper.getMainLooper())) { count++ }
        monitor.configurationChanged()
        monitor.close()
        monitor.configurationChanged()
        ShadowDisplayManager.addDisplay("w400dp-h400dp")
        advance(500)
        assertEquals(0, count)
    }

    @Test fun environmentSnapshotDetectsDensityChangeWithoutMutatingPreviousSnapshot() {
        val before = WidgetDisplayEnvironment.read(context)
        val metrics = context.resources.displayMetrics
        metrics.densityDpi += 1
        val after = WidgetDisplayEnvironment.read(context)
        assertNotEquals(before, after)
        assertEquals(before.density + 1, after.density)
    }
}
