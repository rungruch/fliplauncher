package com.fliplauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppShortcutStoreTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(Looper.getMainLooper()).idle()
        AppShortcutStore.preferences(context).edit().clear().commit()
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun canonicalSelectionSurvivesReorderAndDoesNotChangeWidgetBindings() {
        WidgetScreenStore.saveIds(context, listOf(17, 21))
        WidgetScreenStore.setSize(context, 17, WidgetSize.COMPACT)
        val input = listOf(AppShortcut("test.one/.Main", "One"), AppShortcut("test.two/.Main", "Two"))
        AppShortcutStore.save(context, input)
        AppShortcutStore.save(context, AppShortcutStore.load(context).reversed())

        assertEquals(listOf("Two", "One"), AppShortcutStore.load(context).map { it.label })
        assertEquals(listOf("test.two/test.two.Main", "test.one/test.one.Main"), AppShortcutStore.load(context).map { it.component })
        assertEquals(listOf(17, 21), WidgetScreenStore.ids(context))
        assertEquals(WidgetSize.COMPACT, WidgetScreenStore.size(context, 17))
    }

    @Test fun malformedDuplicateAndExcessSelectionsCannotEnterTheStrip() {
        val values = listOf(AppShortcut("invalid", "Bad"), AppShortcut("/Main", "Bad"),
            AppShortcut("test.one/.Main", "One"), AppShortcut("test.one/test.one.Main", "Duplicate")) +
            (2..7).map { AppShortcut("test.app$it/.Main", "App $it") }
        AppShortcutStore.save(context, values)

        assertEquals(4, AppShortcutStore.load(context).size)
        assertEquals("One", AppShortcutStore.load(context).first().label)
        assertFalse(AppShortcutStore.contains(context, "invalid"))
    }

    @Test fun removalAndRestorationKeepSavedPositionWhileLabelsRefresh() {
        val first = ShortcutTestApps.install(context, "one", "Old label")
        val second = ShortcutTestApps.install(context, "two", "Two")
        val firstComponent = ShortcutTestApps.component(first)
        AppShortcutStore.save(context, listOf(AppShortcut(firstComponent, "Old label"),
            AppShortcut(ShortcutTestApps.component(second), "Two")))
        assertNotNull(AppShortcutStore.entries(context)[0].app)

        ShortcutTestApps.remove(context, first)
        AppShortcutStore.catalogChanged(context)
        assertNull(AppShortcutStore.entries(context)[0].app)
        assertEquals(listOf("Old label", "Two"), AppShortcutStore.load(context).map { it.label })

        ShortcutTestApps.install(context, "one", "New label")
        AppShortcutStore.catalogChanged(context)
        assertEquals(firstComponent, AppShortcutStore.load(context)[0].component)
        assertEquals("New label", AppShortcutStore.load(context)[0].label)
        assertNotNull(AppShortcutStore.entries(context)[0].app)
    }

    @Test fun suspendedAndDisabledAppsAreUnavailableWithoutDeletingSelection() {
        val app = ShortcutTestApps.install(context, "one", "One")
        AppShortcutStore.save(context, listOf(AppShortcut(ShortcutTestApps.component(app), "One")))
        app.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_SUSPENDED
        AppShortcutStore.catalogChanged(context)
        assertNull(AppShortcutStore.entries(context).single().app)
        app.activityInfo.applicationInfo.flags = 0
        app.activityInfo.enabled = false
        AppShortcutStore.catalogChanged(context)
        assertNull(AppShortcutStore.entries(context).single().app)
        assertEquals("One", AppShortcutStore.load(context).single().label)
    }
}
