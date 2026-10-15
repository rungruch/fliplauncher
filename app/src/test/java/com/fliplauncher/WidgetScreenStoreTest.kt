package com.fliplauncher

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetScreenStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun legacyIdsMigrateToSortedPositiveIds() {
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE)
            .edit().putStringSet("widget_ids", setOf("9", "2", "bad", "0", "-1")).commit()

        assertEquals(listOf(2, 9), WidgetScreenStore.ids(context))
    }

    @Test
    fun orderedIdsKeepOrderAndDropInvalidAndDuplicateValues() {
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE)
            .edit().putString("ordered_widget_ids", "[9,0,-2,4,9]").commit()

        assertEquals(listOf(9, 4), WidgetScreenStore.ids(context))
    }

    @Test
    fun malformedSavedIdsAndPendingSetupAreIgnored() {
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE)
            .edit()
            .putString("ordered_widget_ids", "not-json")
            .putString("pending_setup", "{broken")
            .commit()

        assertEquals(emptyList<Int>(), WidgetScreenStore.ids(context))
        assertNull(WidgetScreenStore.pending(context))
    }

    @Test
    fun pendingSetupSurvivesStoreReload() {
        val pending = PendingWidgetSetup(
            id = 17,
            owner = "editor-owner",
            provider = "example.widgets/example.widgets.ClockProvider",
            existing = false,
            stage = "CONFIGURE_READY"
        )

        WidgetScreenStore.setPending(context, pending)

        assertEquals(pending, WidgetScreenStore.pending(context))
    }

    @Test
    fun completingNewSetupAppendsIdAndInitializesItsMetadata() {
        WidgetScreenStore.saveIds(context, listOf(8, 3))
        val pending = PendingWidgetSetup(
            id = 12,
            owner = "editor-owner",
            provider = "example.widgets/example.widgets.CalendarProvider",
            existing = false,
            stage = "CONFIGURE"
        )
        WidgetScreenStore.setPending(context, pending)

        WidgetScreenStore.completeSetup(context, pending, "Calendar")

        assertEquals(listOf(8, 3, 12), WidgetScreenStore.ids(context))
        assertEquals(WidgetSize.AUTO, WidgetScreenStore.size(context, 12))
        // Screens saved before AUTO existed keep their full-page layout.
        assertEquals(WidgetSize.FULL, WidgetScreenStore.size(context, 8))
        assertEquals("Calendar", WidgetScreenStore.label(context, 12))
        assertNull(WidgetScreenStore.pending(context))
    }

    @Test
    fun completingReconfigurationPreservesOrderAndSize() {
        WidgetScreenStore.saveIds(context, listOf(8, 3))
        WidgetScreenStore.setSize(context, 3, WidgetSize.COMPACT)
        WidgetScreenStore.setLabel(context, 3, "Old label")
        val pending = PendingWidgetSetup(
            id = 3,
            owner = "editor-owner",
            provider = "example.widgets/example.widgets.ClockProvider",
            existing = true,
            stage = "CONFIGURE"
        )
        WidgetScreenStore.setPending(context, pending)

        WidgetScreenStore.completeSetup(context, pending, "Updated clock")

        assertEquals(listOf(8, 3), WidgetScreenStore.ids(context))
        assertEquals(WidgetSize.COMPACT, WidgetScreenStore.size(context, 3))
        assertEquals("Updated clock", WidgetScreenStore.label(context, 3))
        assertNull(WidgetScreenStore.pending(context))
    }

    @Test
    fun cancelingPendingNewSetupDoesNotPublishUncommittedId() {
        WidgetScreenStore.saveIds(context, listOf(8))
        WidgetScreenStore.setPending(context, PendingWidgetSetup(
            id = 12,
            owner = "editor-owner",
            provider = "example.widgets/example.widgets.ClockProvider",
            existing = false,
            stage = "BIND"
        ))

        WidgetScreenStore.setPending(context, null)

        assertEquals(listOf(8), WidgetScreenStore.ids(context))
        assertNull(WidgetScreenStore.pending(context))
    }

    @Test
    fun cancelingReconfigurationLeavesExistingWidgetStateUntouched() {
        WidgetScreenStore.saveIds(context, listOf(8, 3))
        WidgetScreenStore.setSize(context, 3, WidgetSize.MEDIUM)
        WidgetScreenStore.setLabel(context, 3, "Clock")
        WidgetScreenStore.setPending(context, PendingWidgetSetup(
            id = 3,
            owner = "editor-owner",
            provider = "example.widgets/example.widgets.ClockProvider",
            existing = true,
            stage = "CONFIGURE"
        ))

        WidgetScreenStore.setPending(context, null)

        assertEquals(listOf(8, 3), WidgetScreenStore.ids(context))
        assertEquals(WidgetSize.MEDIUM, WidgetScreenStore.size(context, 3))
        assertEquals("Clock", WidgetScreenStore.label(context, 3))
    }
}
