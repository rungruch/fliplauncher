package com.fliplauncher

import android.content.ComponentName
import android.content.Context

/**
 * Favorites storage, kept per display type (cover vs main) following the
 * CoverSyncManager model. Components are stored flattened
 * ("package/class").
 */
object FavoritesManager {

    private const val PREFS = "fliplauncher_favorites"

    private fun key(type: CoverSyncManager.DisplayType): String =
        "favorites_${type.name.lowercase()}"

    fun getFavorites(
        context: Context,
        type: CoverSyncManager.DisplayType
    ): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key(type), emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    fun isFavorite(
        context: Context,
        type: CoverSyncManager.DisplayType,
        component: ComponentName
    ): Boolean = getFavorites(context, type).contains(component.flattenToString())

    /** Toggles; returns true if now a favorite. */
    fun toggleFavorite(
        context: Context,
        type: CoverSyncManager.DisplayType,
        component: ComponentName
    ): Boolean {
        val set = getFavorites(context, type)
        val flat = component.flattenToString()
        val nowFavorite = if (set.contains(flat)) {
            set.remove(flat)
            false
        } else {
            set.add(flat)
            true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(key(type), set)
            .apply()
        return nowFavorite
    }

    fun removeFavorite(
        context: Context,
        type: CoverSyncManager.DisplayType,
        component: ComponentName
    ) {
        val set = getFavorites(context, type)
        if (set.remove(component.flattenToString())) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(key(type), set)
                .apply()
        }
    }

    /** Maps stored favorites back onto loaded AppInfo entries, in stored order. */
    fun resolveFavorites(
        context: Context,
        type: CoverSyncManager.DisplayType,
        allApps: List<AppInfo>
    ): List<AppInfo> {
        val flats = getFavorites(context, type)
        val byFlat = allApps.associateBy { it.component.flattenToString() }
        return flats.mapNotNull { byFlat[it] }
    }
}
