package com.fliplauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppShortcutEditorAdapter(private val onManage: (AppShortcutEntry) -> Unit) : RecyclerView.Adapter<AppShortcutEditorAdapter.Holder>() {
    private var entries = emptyList<AppShortcutEntry>()
    fun submit(entries: List<AppShortcutEntry>) { this.entries = entries; notifyDataSetChanged() }
    override fun getItemCount() = entries.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_app_shortcut_editor, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        if (entry.app == null) holder.icon.setImageResource(R.drawable.ic_app_shortcut) else holder.icon.setImageDrawable(entry.app.icon)
        holder.label.text = entry.label
        holder.detail.setText(if (entry.app == null) R.string.shortcut_unavailable_detail else R.string.shortcut_ready_detail)
        holder.options.contentDescription = holder.itemView.context.getString(R.string.manage_app, entry.label)
        holder.options.setOnClickListener { onManage(entry) }
        holder.itemView.setOnClickListener { onManage(entry) }
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.editorShortcutIcon)
        val label: TextView = view.findViewById(R.id.editorShortcutLabel)
        val detail: TextView = view.findViewById(R.id.editorShortcutDetail)
        val options: View = view.findViewById(R.id.editorShortcutOptions)
    }
}
