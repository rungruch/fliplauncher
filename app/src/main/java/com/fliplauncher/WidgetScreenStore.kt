package com.fliplauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** AUTO uses the provider's declared default height; the others scale to the available surface. */
enum class WidgetSize(val fraction: Float?) { AUTO(null), COMPACT(0.5f), MEDIUM(0.75f), FULL(1f) }

data class PendingWidgetSetup(
    val id: Int, val owner: String, val provider: String,
    val existing: Boolean, val stage: String
)

/** Widget bindings belong to host 1024; the order and presentation belong to this store. */
object WidgetScreenStore {
    const val HOST_ID = 1024
    const val MAX_WIDGETS = 12
    private const val PREFS = "fliplauncher_widgets"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(context: Context): List<Int> {
        val prefs = prefs(context)
        val encoded = prefs.getString("ordered_widget_ids", null)
        return if (encoded == null) {
            // Migration from the v0.2 host scaffold, whose set had no defined order.
            prefs.getStringSet("widget_ids", emptySet()).orEmpty()
                .mapNotNull(String::toIntOrNull).filter { it > 0 }.sorted()
        } else runCatching {
            val values = JSONArray(encoded)
            (0 until values.length()).map { values.optInt(it, -1) }.filter { it > 0 }.distinct()
        }.getOrDefault(emptyList())
    }

    fun saveIds(context: Context, ids: List<Int>) {
        prefs(context).edit().putString("ordered_widget_ids", JSONArray(ids.distinct()).toString())
            .remove("widget_ids").apply()
    }

    fun size(context: Context, id: Int): WidgetSize = runCatching {
        WidgetSize.valueOf(prefs(context).getString("size_$id", WidgetSize.FULL.name)!!)
    }.getOrDefault(WidgetSize.FULL)

    fun setSize(context: Context, id: Int, size: WidgetSize) {
        prefs(context).edit().putString("size_$id", size.name).apply()
    }

    fun label(context: Context, id: Int): String =
        prefs(context).getString("label_$id", context.getString(R.string.widget_unavailable))!!

    fun setLabel(context: Context, id: Int, label: String) {
        if (prefs(context).getString("label_$id", null) != label)
            prefs(context).edit().putString("label_$id", label).apply()
    }

    fun forget(context: Context, id: Int) {
        saveIds(context, ids(context).filter { it != id })
        prefs(context).edit().remove("size_$id").remove("label_$id").apply()
    }

    fun pending(context: Context): PendingWidgetSetup? = runCatching {
        val value = prefs(context).getString("pending_setup", null) ?: return null
        val json = JSONObject(value)
        PendingWidgetSetup(json.getInt("id"), json.getString("owner"), json.getString("provider"),
            json.getBoolean("existing"), json.getString("stage"))
    }.getOrNull()

    fun setPending(context: Context, pending: PendingWidgetSetup?) {
        val edit = prefs(context).edit()
        if (pending == null) edit.remove("pending_setup")
        else edit.putString("pending_setup", JSONObject().apply {
            put("id", pending.id); put("owner", pending.owner); put("provider", pending.provider)
            put("existing", pending.existing); put("stage", pending.stage)
        }.toString())
        edit.apply()
    }

    fun completeSetup(context: Context, pending: PendingWidgetSetup, label: String) {
        // Commit the new screen and clear its setup record in one preference transaction.
        val ids = if (pending.existing) ids(context) else (ids(context) + pending.id).distinct()
        val edit = prefs(context).edit()
            .putString("ordered_widget_ids", JSONArray(ids).toString()).remove("widget_ids")
            .putString("label_${pending.id}", label).remove("pending_setup")
        if (!pending.existing) edit.putString("size_${pending.id}", WidgetSize.AUTO.name)
        edit.apply()
    }

    fun remap(context: Context, oldIds: IntArray, newIds: IntArray) {
        val mapping = oldIds.zip(newIds.toList()).toMap()
        val ordered = ids(context)
        val state = ordered.map { Triple(mapping[it] ?: it, size(context, it), label(context, it)) }
        saveIds(context, state.map { it.first })
        state.forEach { (id, size, label) -> setSize(context, id, size); setLabel(context, id, label) }
        setPending(context, null)
    }
}
