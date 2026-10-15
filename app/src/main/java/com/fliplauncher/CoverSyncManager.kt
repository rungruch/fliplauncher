package com.fliplauncher

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics

/**
 * Per-display home data model, inspired by Samsung's CoverSyncHelper
 * (com.honeyspace.common.interfaces.CoverSyncHelper) found in One UI Home:
 * the launcher keeps separate data per display type and offers a
 * "cover/main sync" toggle. Clean-room reimplementation of the concept —
 * no Samsung code is used.
 */
object CoverSyncManager {

    enum class DisplayType { COVER, MAIN }

    private const val PREFS = "fliplauncher_sync"
    private const val KEY_SYNC_ENABLED = "cover_main_sync_enabled"

    /** Mirrors CoverSyncHelper.isCoverMainSyncEnabled(). */
    fun isCoverMainSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, false)

    fun setCoverMainSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYNC_ENABLED, enabled)
            .apply()
    }

    /**
     * Mirrors CoverSyncHelper.getCurrentDisplay(): classifies the display the
     * given context is running on. The smallest-area display is the cover.
     */
    fun getCurrentDisplay(context: Context): DisplayType {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return DisplayType.COVER
        val areas = dm.displays.map { display ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(m)
            display.displayId to (m.widthPixels * m.heightPixels)
        }
        if (areas.isEmpty()) return DisplayType.COVER
        val smallest = areas.minByOrNull { it.second }!!.first
        val currentId: Int = context.display.displayId
        return if (currentId == smallest) DisplayType.COVER else DisplayType.MAIN
    }

    /**
     * Mirrors CoverSyncHelper.useCoverData(): true when the UI should use
     * cover-specific data rather than the shared/main data.
     */
    fun useCoverData(context: Context): Boolean =
        getCurrentDisplay(context) == DisplayType.COVER && !isCoverMainSyncEnabled(context)

    /**
     * Resolves which display type's data backs the favorites row right now.
     * With sync enabled, the cover mirrors the main screen's favorites.
     */
    fun favoritesDisplayType(context: Context): DisplayType =
        if (useCoverData(context)) DisplayType.COVER else DisplayType.MAIN
}
