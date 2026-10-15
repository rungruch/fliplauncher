package com.fliplauncher

import android.appwidget.AppWidgetProviderInfo
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSizingTest {
    private fun info() = AppWidgetProviderInfo().apply { minWidth = 160; minHeight = 180 }

    @Test fun fixedHeightDoesNotExpandWithSavedSizeOrViewport() {
        val info = info()
        WidgetSize.entries.forEach { size ->
            val layout = WidgetSizing.calculate(info, size, 400, 700, 2f)
            assertTrue(layout.fits)
            assertEquals(180, layout.height)
        }
    }

    @Test fun resizableHeightHonorsProviderMinimumAndMaximum() {
        val info = info().apply {
            resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL
            minResizeHeight = 120; maxResizeHeight = 400
        }
        assertEquals(120, WidgetSizing.calculate(info, WidgetSize.COMPACT, 400, 200, 1f).height)
        assertEquals(300, WidgetSizing.calculate(info, WidgetSize.MEDIUM, 400, 400, 1f).height)
        assertEquals(400, WidgetSizing.calculate(info, WidgetSize.FULL, 400, 800, 1f).height)
    }

    @Test fun autoUsesTheProviderDefaultHeightRatherThanTheSurface() {
        val info = info().apply { resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL; minResizeHeight = 60 }
        // Neither the shrinkable minimum nor the tall cover surface: the declared default.
        assertEquals(180, WidgetSizing.calculate(info, WidgetSize.AUTO, 400, 600, 1f, scrolling = true).height)
        assertEquals(196, WidgetSizing.calculate(info, WidgetSize.AUTO, 416, 616, 1f, Rect(8, 8, 8, 8)).height)
        // Tiny defaults still get the 48dp touch floor.
        info.minHeight = 20
        assertEquals(96, WidgetSizing.calculate(info, WidgetSize.AUTO, 400, 600, 2f).height)
    }

    @Test fun invalidResizeBoundsAreIgnored() {
        val info = info().apply {
            resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
            minResizeHeight = 300; maxResizeHeight = 100
            minResizeWidth = 300; maxResizeWidth = 100
        }
        val layout = WidgetSizing.calculate(info, WidgetSize.FULL, 400, 500, 1f)
        assertTrue(layout.fits)
        assertEquals(400, layout.width)
        assertEquals(500, layout.height)
    }

    @Test fun horizontalResizeMinimumControlsCoverFit() {
        val info = info().apply { minWidth = 600; minResizeWidth = 300 }
        assertFalse(WidgetSizing.calculate(info, WidgetSize.FULL, 400, 500, 1f).fits)
        info.resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL
        assertTrue(WidgetSizing.calculate(info, WidgetSize.FULL, 400, 500, 1f).fits)
        assertFalse(WidgetSizing.calculate(info, WidgetSize.FULL, 280, 500, 1f).fits)
    }

    @Test fun horizontalMaximumLimitsContentWidth() {
        val info = info().apply { resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL; maxResizeWidth = 250 }
        assertEquals(250, WidgetSizing.calculate(info, WidgetSize.FULL, 400, 500, 1f).width)
    }

    @Test fun tallWidgetCanScrollNativelyButDoesNotGetUndersizedDashboardHints() {
        val info = info().apply { minHeight = 600 }
        val dashboard = WidgetSizing.calculate(info, WidgetSize.FULL, 400, 300, 1f)
        val native = WidgetSizing.calculate(info, WidgetSize.FULL, 400, 300, 1f, scrolling = true)
        assertFalse(dashboard.fits)
        assertTrue(native.fits)
        assertEquals(600, native.height)
    }

    @Test fun nativeAndDashboardContentSizesAgreeAfterHostPadding() {
        val info = info()
        val native = WidgetSizing.calculate(info, WidgetSize.FULL, 400, 500, 2f)
        val dashboard = WidgetSizing.calculate(info, WidgetSize.FULL, 416, 516, 2f, Rect(8, 8, 8, 8))
        assertEquals(native.width, dashboard.width - 16)
        assertEquals(native.height, dashboard.height - 16)
        assertEquals(90f, native.dp(2f).height, 0f)
    }

    @Test fun resizableContentFractionAlsoExcludesHostPadding() {
        val info = info().apply { resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL; minResizeHeight = 100 }
        val native = WidgetSizing.calculate(info, WidgetSize.COMPACT, 400, 500, 2f)
        val dashboard = WidgetSizing.calculate(info, WidgetSize.COMPACT, 416, 516, 2f, Rect(8, 8, 8, 8))
        assertEquals(native.height, dashboard.height - 16)
    }

    @Test fun malformedHugeProviderCannotCreateUnboundedNativeRow() {
        val info = info().apply { minHeight = 5000 }
        val layout = WidgetSizing.calculate(info, WidgetSize.FULL, 400, 300, 1f, scrolling = true)
        assertFalse(layout.fits)
        assertEquals(1200, layout.height)
    }
}
