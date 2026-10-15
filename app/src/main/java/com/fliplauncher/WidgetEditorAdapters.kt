package com.fliplauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(private val onToggle: (AppInfo) -> Unit) : RecyclerView.Adapter<AppPickerAdapter.Holder>() {
    private var apps: List<AppInfo> = emptyList()
    private var selected: Set<String> = emptySet()

    fun submit(apps: List<AppInfo>, selected: Set<String>) {
        this.apps = apps
        this.selected = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_picker_app, parent, false))

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        val checked = app.component.flattenToString() in selected
        holder.icon.setImageDrawable(app.icon)
        holder.label.text = app.label
        holder.check.visibility = if (checked) View.VISIBLE else View.GONE
        holder.itemView.isSelected = checked
        holder.itemView.contentDescription = app.label
        ViewCompat.setStateDescription(holder.itemView, holder.itemView.context.getString(
            if (checked) R.string.selected_state else R.string.not_selected_state))
        ViewCompat.setAccessibilityDelegate(holder.itemView, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.CheckBox"
                info.isCheckable = true
                info.isChecked = checked
            }
        })
        holder.itemView.setOnClickListener { onToggle(app) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.pickerAppIcon)
        val label: TextView = view.findViewById(R.id.pickerAppLabel)
        val check: ImageView = view.findViewById(R.id.pickerAppCheck)
    }
}

class SelectedAppsAdapter(
    private val onRemove: (AppInfo) -> Unit,
    private val onManage: (AppInfo) -> Unit
) : RecyclerView.Adapter<SelectedAppsAdapter.Holder>() {
    private val apps = mutableListOf<AppInfo>()

    fun submit(apps: List<AppInfo>) {
        this.apps.clear()
        this.apps.addAll(apps)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        apps.add(to, apps.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_selected_app, parent, false))

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.icon.contentDescription = holder.itemView.context.getString(R.string.manage_app, app.label)
        holder.icon.setOnClickListener { onManage(app) }
        holder.remove.contentDescription = holder.itemView.context.getString(R.string.remove_app, app.label)
        holder.remove.setOnClickListener { onRemove(app) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.selectedAppIcon)
        val remove: ImageButton = view.findViewById(R.id.selectedAppRemove)
    }
}

class CoverAppsAdapter(private val onLaunch: (AppInfo) -> Unit) : RecyclerView.Adapter<CoverAppsAdapter.Holder>() {
    private var apps: List<AppInfo> = emptyList()

    fun submit(apps: List<AppInfo>) {
        this.apps = apps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.widget_app_item, parent, false))

    override fun getItemCount() = apps.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.itemView.contentDescription = app.label
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { onLaunch(app) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.coverAppIcon)
    }
}
