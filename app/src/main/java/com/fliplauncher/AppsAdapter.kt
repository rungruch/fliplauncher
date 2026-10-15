package com.fliplauncher

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Grid adapter for the cover-screen app drawer. */
class AppsAdapter(
    private val onAppClicked: (AppInfo) -> Unit,
    private val onAppLongClicked: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private var apps: List<AppInfo> = emptyList()
    private var filtered: List<AppInfo> = emptyList()

    fun submitList(newApps: List<AppInfo>) {
        apps = newApps
        filtered = newApps
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filtered = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view, onAppClicked, onAppLongClicked)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(filtered[position])
    }

    override fun getItemCount(): Int = filtered.size

    class AppViewHolder(
        itemView: View,
        private val onAppClicked: (AppInfo) -> Unit,
        private val onAppLongClicked: (AppInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val label: TextView = itemView.findViewById(R.id.appLabel)

        fun bind(app: AppInfo) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            itemView.setOnClickListener { onAppClicked(app) }
            itemView.setOnLongClickListener { onAppLongClicked(app); true }
        }
    }
}
