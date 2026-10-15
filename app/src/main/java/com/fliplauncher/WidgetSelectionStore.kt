package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray

data class WidgetSelection(val components: List<String>, val transparent: Boolean = true)

/** JSON arrays preserve icon order. Each widget has independent saved settings. */
object WidgetSelectionStore {
    const val MAX_APPS = 24
    const val DEFAULT_PROFILE = AppWidgetManager.INVALID_APPWIDGET_ID
    private const val PREFS = "fliplauncher_widget_selection"

    private fun prefix(widgetId: Int) = if (widgetId == DEFAULT_PROFILE) "default" else "widget_$widgetId"

    fun load(context: Context, widgetId: Int): WidgetSelection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(widgetId)
        val value = prefs.getString("${prefix}_apps", null)
        if (value == null && widgetId != DEFAULT_PROFILE) return load(context, DEFAULT_PROFILE)
        val components = if (value == null) {
            // The old favorites format had no defined order; migrate it as a sorted list.
            FavoritesManager.getFavorites(context, CoverSyncManager.DisplayType.COVER).sorted()
        } else {
            runCatching {
                val array = JSONArray(value)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.getOrDefault(emptyList())
        }
        return WidgetSelection(normalize(components), prefs.getBoolean("${prefix}_transparent", true))
    }

    fun save(context: Context, widgetId: Int, selection: WidgetSelection) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${prefix(widgetId)}_apps", JSONArray(normalize(selection.components)).toString())
            .putBoolean("${prefix(widgetId)}_transparent", selection.transparent)
            .apply()
    }

    fun delete(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${prefix(widgetId)}_apps")
            .remove("${prefix(widgetId)}_transparent")
            .apply()
    }

    fun normalize(components: List<String>): List<String> = components
        .mapNotNull { ComponentName.unflattenFromString(it)?.flattenToString() }
        .distinct().take(MAX_APPS)
}
