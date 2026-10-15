package com.fliplauncher

import java.text.Collator

data class WidgetProviderGroup(val packageName: String, val label: String, val widgets: List<WidgetProviderEntry>)

sealed class WidgetPickerRow(val key: String) {
    data class Header(val group: WidgetProviderGroup, val expanded: Boolean, val canToggle: Boolean) :
        WidgetPickerRow("app:${group.packageName}")
    data class Provider(val entry: WidgetProviderEntry) :
        WidgetPickerRow("widget:${entry.info.provider.flattenToString()}")
}

/** The catalog is personal-profile only. Package/component identities never depend on labels. */
object WidgetPickerModel {
    fun groups(entries: List<WidgetProviderEntry>, query: String, packageName: String?): List<WidgetProviderGroup> {
        val matcher = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val term = query.trim()
        return entries.asSequence().filter { packageName == null || it.info.provider.packageName == packageName }
            .distinctBy { it.info.provider }.groupBy { it.info.provider.packageName }
            .mapNotNull { (pkg, variants) ->
                val label = variants.first().appLabel
                val matching = if (label.contains(term, ignoreCase = true)) variants
                    else variants.filter { it.label.contains(term, ignoreCase = true) }
                matching.takeIf { it.isNotEmpty() }?.let {
                    WidgetProviderGroup(pkg, label, it.sortedWith { a, b ->
                        matcher.compare(a.label, b.label).takeIf { order -> order != 0 }
                            ?: a.info.provider.flattenToString().compareTo(b.info.provider.flattenToString())
                    })
                }
            }.sortedWith { a, b ->
                matcher.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
            }
    }

    fun rows(groups: List<WidgetProviderGroup>, expanded: Set<String>, searching: Boolean): List<WidgetPickerRow> =
        groups.flatMap { group ->
            val open = searching || group.packageName in expanded
            listOf(WidgetPickerRow.Header(group, open, !searching)) +
                if (open) group.widgets.map(WidgetPickerRow::Provider) else emptyList()
        }
}
