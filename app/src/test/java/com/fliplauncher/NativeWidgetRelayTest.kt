package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RemoteViews
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
class NativeWidgetRelayTest {
    private lateinit var context: Context
    private lateinit var info: AppWidgetProviderInfo

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        NativeWidgetRelay.retain(emptySet())
        info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context.packageName, "TestWidget")
            minWidth = 100; minHeight = 100
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(7, info)
        WidgetScreenStore.saveIds(context, listOf(7))
    }

    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))

    @Test fun compatiblePayloadSurvivesTemporaryNullAndIsRecheckedOnContextChange() {
        NativeWidgetRelay.capture(context, 7, info, RemoteViews(context.packageName, R.layout.widget_dashboard_shortcut))
        advance()
        val before = NativeWidgetRelay.entry(7, info.provider.flattenToString())
        assertNotNull(before?.source)
        assertFalse(before!!.lenient)
        NativeWidgetRelay.capture(context, 7, info, null)
        NativeWidgetRelay.reclassify(context)
        advance()
        assertNotNull(NativeWidgetRelay.entry(7, info.provider.flattenToString())?.source)
    }

    @Test fun removingWidgetBeforeClassificationCannotRepublishIt() {
        NativeWidgetRelay.capture(context, 7, info, RemoteViews(context.packageName, R.layout.widget_dashboard_shortcut))
        NativeWidgetRelay.forget(context, 7)
        WidgetScreenStore.forget(context, 7)
        advance()
        assertNull(NativeWidgetRelay.entry(7, info.provider.flattenToString()))
    }

    @Test fun replacementProviderCannotReceiveOldPayload() {
        NativeWidgetRelay.capture(context, 7, info, RemoteViews(context.packageName, R.layout.widget_dashboard_shortcut))
        val replacement = AppWidgetProviderInfo().apply { provider = ComponentName(context.packageName, "Replacement") }
        shadowOf(AppWidgetManager.getInstance(context)).putWidgetInfo(7, replacement)
        advance()
        assertNull(NativeWidgetRelay.entry(7, replacement.provider.flattenToString()))
        assertNull(NativeWidgetRelay.entry(7, info.provider.flattenToString()))
    }

    @Test fun lenientRowContainerAcceptsProviderRootWithUnresolvedSize() {
        // A provider's @dimen layout_height resolves to nothing in our resources: the attribute
        // is effectively missing, which a FrameLayout parent rejects and GridLayout defaults.
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.layout_width, "match_parent").build()
        val row = LayoutInflater.from(context).inflate(R.layout.item_native_widget, null)
        val plain = row.findViewById<ViewGroup>(R.id.nativeWidgetContent)
        val lenient = row.findViewById<ViewGroup>(R.id.nativeWidgetContentLenient)
        assertThrows(RuntimeException::class.java) { plain.generateLayoutParams(attrs) }
        val params = lenient.generateLayoutParams(attrs)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test fun nativeWidthUsesCurrentCoverBoundsWhenHostHintsAreStale() {
        val cover = ShadowDisplayManager.addDisplay("w200dp-h240dp")
        assertEquals(DisplayHelper.FLIP5_COVER_DISPLAY_ID, cover)
        val surface = NativeWidgetSizing.surface(context)
        assertEquals(184, surface.width)
        assertEquals(192, surface.height)
    }
}
