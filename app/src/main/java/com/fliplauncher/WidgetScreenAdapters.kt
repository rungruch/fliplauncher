package com.fliplauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

fun WidgetSize.labelResource(): Int = when (this) {
    WidgetSize.AUTO -> R.string.widget_size_auto
    WidgetSize.COMPACT -> R.string.widget_size_compact
    WidgetSize.MEDIUM -> R.string.widget_size_medium
    WidgetSize.FULL -> R.string.widget_size_full
}

class WidgetPickerAdapter(private val previews: WidgetPreviewLoader,
    private val onToggle: (String) -> Unit,
    private val onChoose: (WidgetProviderEntry) -> Unit) : ListAdapter<WidgetPickerRow, RecyclerView.ViewHolder>(DIFF) {
    private val ids = mutableMapOf<String, Long>()
    init { setHasStableIds(true); stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    override fun getItemId(position: Int) = ids.getOrPut(getItem(position).key) { ids.size.toLong() }
    override fun getItemViewType(position: Int) = if (getItem(position) is WidgetPickerRow.Header) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderHolder(inflater.inflate(R.layout.item_widget_app_header, parent, false))
            else Holder(inflater.inflate(R.layout.item_widget_provider, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder is HeaderHolder && row is WidgetPickerRow.Header) {
            holder.label.text = row.group.label
            holder.count.text = holder.itemView.resources.getQuantityString(R.plurals.widget_variant_count,
                row.group.widgets.size, row.group.widgets.size)
            holder.toggle.setText(if (row.expanded) R.string.hide_widgets else R.string.show_widgets)
            holder.toggle.visibility = if (row.canToggle) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener(if (row.canToggle) View.OnClickListener { onToggle(row.group.packageName) } else null)
            holder.itemView.isClickable = row.canToggle
            holder.itemView.isFocusable = row.canToggle
            ViewCompat.setStateDescription(holder.itemView, holder.itemView.context.getString(
                if (row.expanded) R.string.widgets_expanded else R.string.widgets_collapsed))
            return
        }
        holder as Holder
        val entry = (row as WidgetPickerRow.Provider).entry
        holder.label.text = entry.label
        holder.app.text = entry.appLabel
        val info = entry.info
        holder.cells.text = if (info.targetCellWidth > 0 && info.targetCellHeight > 0)
            holder.itemView.context.getString(R.string.widget_cells, info.targetCellWidth, info.targetCellHeight)
            else ""
        holder.itemView.setOnClickListener { onChoose(entry) }
        previews.load(info, holder.preview)
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Holder) previews.clear(holder.preview)
        super.onViewRecycled(holder)
    }
    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.widgetAppLabel)
        val count: TextView = view.findViewById(R.id.widgetAppCount)
        val toggle: TextView = view.findViewById(R.id.widgetAppToggle)
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val preview: ImageView = view.findViewById(R.id.providerPreview)
        val label: TextView = view.findViewById(R.id.providerLabel)
        val app: TextView = view.findViewById(R.id.providerApp)
        val cells: TextView = view.findViewById(R.id.providerCells)
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WidgetPickerRow>() {
            override fun areItemsTheSame(oldItem: WidgetPickerRow, newItem: WidgetPickerRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: WidgetPickerRow, newItem: WidgetPickerRow) = oldItem == newItem
        }
    }
}

class WidgetScreenAdapter(private val previews: WidgetPreviewLoader,
    private val onManage: (HostedWidget) -> Unit) : RecyclerView.Adapter<WidgetScreenAdapter.Holder>() {
    private val widgets = mutableListOf<HostedWidget>()
    init { setHasStableIds(true) }
    fun submit(widgets: List<HostedWidget>) {
        this.widgets.clear(); this.widgets.addAll(widgets); notifyDataSetChanged()
    }
    fun move(from: Int, to: Int) { widgets.add(to, widgets.removeAt(from)); notifyItemMoved(from, to) }
    override fun getItemId(position: Int) = widgets[position].id.toLong()
    override fun getItemCount() = widgets.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_widget_screen, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val widget = widgets[position]
        holder.label.text = widget.label
        holder.detail.text = holder.itemView.context.getString(R.string.widget_screen_detail,
            position + 1, holder.itemView.context.getString(widget.size.labelResource()))
        holder.options.contentDescription = holder.itemView.context.getString(R.string.widget_options, widget.label)
        holder.options.setOnClickListener { onManage(widget) }
        holder.itemView.setOnClickListener { onManage(widget) }
        previews.load(widget.info, holder.preview)
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.screenLabel)
        val detail: TextView = view.findViewById(R.id.screenDetail)
        val preview: ImageView = view.findViewById(R.id.screenPreview)
        val options: View = view.findViewById(R.id.screenOptions)
    }
}
