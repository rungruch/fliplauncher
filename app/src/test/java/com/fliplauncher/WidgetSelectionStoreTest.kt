package com.fliplauncher

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSelectionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf("fliplauncher_widget_selection", "fliplauncher_favorites").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun normalizeDropsMalformedAndDuplicateComponentsAndEnforcesLimit() {
        val valid = (0..WidgetSelectionStore.MAX_APPS).map { component(it).flattenToString() }
        val values = listOf("not-a-component", valid[0], valid[0]) + valid.drop(1)

        val result = WidgetSelectionStore.normalize(values)

        assertEquals(WidgetSelectionStore.MAX_APPS, result.size)
        assertEquals(valid.take(WidgetSelectionStore.MAX_APPS), result)
    }

    @Test
    fun missingWidgetProfileFallsBackToDefaultSelection() {
        val default = WidgetSelection(listOf(component(1).flattenToString()), transparent = false)
        WidgetSelectionStore.save(context, WidgetSelectionStore.DEFAULT_PROFILE, default)

        assertEquals(default, WidgetSelectionStore.load(context, 42))
    }

    @Test
    fun oldCoverFavoritesMigrateInStableOrder() {
        val favoriteSet = setOf(component(3).flattenToString(), component(1).flattenToString())
        context.getSharedPreferences("fliplauncher_favorites", Context.MODE_PRIVATE)
            .edit().putStringSet("favorites_cover", favoriteSet).commit()

        val result = WidgetSelectionStore.load(context, WidgetSelectionStore.DEFAULT_PROFILE)

        assertEquals(favoriteSet.sorted(), result.components)
        assertTrue(result.transparent)
    }

    @Test
    fun savedSelectionIsNormalizedAndKeepsTransparencySetting() {
        val first = component(1).flattenToString()
        val second = component(2).flattenToString()
        WidgetSelectionStore.save(context, 7, WidgetSelection(
            components = listOf(first, "invalid", first, second),
            transparent = false
        ))

        val result = WidgetSelectionStore.load(context, 7)

        assertEquals(listOf(first, second), result.components)
        assertFalse(result.transparent)
    }

    private fun component(index: Int) = ComponentName("example.app", "example.app.Activity$index")
}
