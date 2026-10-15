package com.fliplauncher

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AppShortcut(val component: String, val label: String)
data class AppShortcutEntry(val shortcut: AppShortcut, val app: AppInfo?) {
    val label: CharSequence get() = app?.label ?: shortcut.label
}

/** Shared by all cover instances and the dashboard, independently of widget bindings. */
object AppShortcutStore {
    const val MAX_SHORTCUTS = 4
    const val CATALOG_REVISION = "catalog_revision"
    private const val ITEMS = "shortcuts"
    private const val AVAILABILITY = "availability"
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("fliplauncher_app_shortcuts", Context.MODE_PRIVATE)

    fun load(context: Context): List<AppShortcut> = runCatching {
        val array = JSONArray(preferences(context).getString(ITEMS, "[]"))
        normalize((0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            AppShortcut(item.optString("component"), item.optString("label"))
        })
    }.getOrDefault(emptyList())

    fun normalize(items: List<AppShortcut>): List<AppShortcut> = items.mapNotNull { item ->
        val name = ComponentName.unflattenFromString(item.component) ?: return@mapNotNull null
        if (name.packageName.isBlank() || name.className.isBlank()) return@mapNotNull null
        val component = name.flattenToString()
        AppShortcut(component, item.label.trim().ifEmpty { name.packageName })
    }.distinctBy { it.component }.take(MAX_SHORTCUTS)

    fun entries(context: Context): List<AppShortcutEntry> = load(context).map {
        AppShortcutEntry(it, AppListLoader.resolve(context, it.component))
    }

    fun contains(context: Context, component: String) = load(context).any { it.component == component }

    fun save(context: Context, items: List<AppShortcut>) {
        val normalized = normalize(items)
        if (normalized == load(context)) return
        write(context, normalized)
        FlipLauncherWidgetProvider.refresh(context)
    }

    private fun write(context: Context, items: List<AppShortcut>) {
        preferences(context).edit().putString(ITEMS, encode(items)).apply()
    }

    private fun encode(items: List<AppShortcut>): String {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("component", it.component).put("label", it.label)) }
        return array.toString()
    }

    /**
     * Missing apps keep their position/label, including transient unavailability during updates.
     * Widgets refresh only when a label or availability changed; [catalogEvent] is false for the
     * process-start reconcile, which has no open picker to notify.
     */
    fun catalogChanged(context: Context, catalogEvent: Boolean = true) {
        val current = load(context)
        val resolved = current.map { it to AppListLoader.resolve(context, it.component) }
        val updated = resolved.map { (shortcut, app) -> app?.let { shortcut.copy(label = it.label.toString()) } ?: shortcut }
        val availability = resolved.joinToString("\n") { (shortcut, app) -> "${shortcut.component}=${app != null}" }
        val prefs = preferences(context)
        val changed = updated != current || availability != prefs.getString(AVAILABILITY, null)
        if (!changed && !catalogEvent) return
        prefs.edit().apply {
            if (updated != current) putString(ITEMS, encode(updated))
            putString(AVAILABILITY, availability)
            if (catalogEvent) putLong(CATALOG_REVISION, prefs.getLong(CATALOG_REVISION, 0) + 1)
        }.apply()
        if (changed) FlipLauncherWidgetProvider.refresh(context)
    }
}
