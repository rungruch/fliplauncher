package com.fliplauncher

import android.appwidget.AppWidgetHostView
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Each RecyclerView item occupies the viewport and contains one interactive host view. */
class WidgetPageAdapter(private val helper: WidgetHelper) : RecyclerView.Adapter<WidgetPageAdapter.Holder>() {
    private var widgets = emptyList<HostedWidget>()
    init { setHasStableIds(true) }
    fun ids() = widgets.map { it.id }
    fun labelAt(position: Int) = widgets.getOrNull(position)?.label.orEmpty()
    fun submit(widgets: List<HostedWidget>) { this.widgets = widgets; notifyDataSetChanged() }
    override fun getItemId(position: Int) = widgets[position].id.toLong()
    override fun getItemCount() = widgets.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cover_widget_page, parent, false)
        return Holder(view, ::layoutWidget)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        detach(holder)
        holder.bound = widgets[position]
        holder.container.removeAllViews()
        holder.lastSize = null
        val hostView = runCatching { helper.createView(widgets[position]) }
            .onFailure { android.util.Log.w("FlipLauncherWidgets", "Cannot create ${widgets[position].info?.provider} widget ${widgets[position].id}", it) }
            .getOrNull()
        holder.hostView = hostView
        holder.title.text = widgets[position].label
        holder.open.setOnClickListener { holder.bound?.let { helper.openApp(it.id) } }
        if (hostView != null) {
            (hostView as? RecoveringWidgetHostView)?.onRenderStateChanged = {
                if (holder.hostView === hostView && holder.bound?.id == hostView.appWidgetId) layoutWidget(holder)
            }
            (hostView.parent as? ViewGroup)?.removeView(hostView)
            holder.container.addView(hostView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        showFallback(holder, if (hostView == null) R.string.widget_unavailable_detail
            else if ((hostView as? RecoveringWidgetHostView)?.hasRenderFailure == true) R.string.widget_render_failed else null)
        holder.container.post { layoutWidget(holder) }
    }
    override fun onViewRecycled(holder: Holder) {
        detach(holder)
        holder.container.removeAllViews(); holder.bound = null; holder.hostView = null
    }
    // Host views are shared per widget id and move between holders; a stale holder must not
    // clear the callback or visibility that the view's current holder now owns.
    private fun owns(holder: Holder) = holder.hostView?.parent === holder.container
    private fun detach(holder: Holder) {
        if (owns(holder)) (holder.hostView as? RecoveringWidgetHostView)?.onRenderStateChanged = null
    }
    private fun showFallback(holder: Holder, message: Int?) {
        holder.fallback.visibility = if (message == null) View.GONE else View.VISIBLE
        if (owns(holder)) holder.hostView?.visibility = if (message == null) View.VISIBLE else View.GONE
        if (message != null) holder.unavailable.setText(message)
        holder.open.visibility = if (holder.bound?.info == null) View.GONE else View.VISIBLE
    }
    private fun layoutWidget(holder: Holder) {
        val widget = holder.bound ?: return
        val info = widget.info ?: return
        val hostView = holder.hostView ?: return
        if (hostView.parent !== holder.container) return
        if ((hostView as? RecoveringWidgetHostView)?.hasRenderFailure == true) {
            holder.lastSize = null
            showFallback(holder, R.string.widget_render_failed)
            return
        }
        val width = holder.container.width; val available = holder.container.height
        if (width <= 0 || available <= 0) return
        val density = holder.container.resources.displayMetrics.density
        val layout = WidgetSizing.calculate(info, widget.size, width, available, density,
            Rect(hostView.paddingLeft, hostView.paddingTop, hostView.paddingRight, hostView.paddingBottom))
        showFallback(holder, if (layout.fits) null else R.string.widget_too_large)
        if (!layout.fits) { holder.lastSize = null; return }
        val size = layout.dp(density)
        if (holder.lastSize == size) return
        holder.lastSize = size
        hostView.layoutParams = FrameLayout.LayoutParams(layout.width, layout.height, Gravity.CENTER)
        runCatching { hostView.updateAppWidgetSize(Bundle(), listOf(size)) }
            .onFailure { android.util.Log.w("FlipLauncherWidgets", "Widget size update failed", it) }
    }
    class Holder(view: View, onLayout: (Holder) -> Unit) : RecyclerView.ViewHolder(view) {
        val container: FrameLayout = view.findViewById(R.id.coverWidgetContainer)
        val fallback: View = view.findViewById(R.id.coverWidgetFallback)
        val title: TextView = view.findViewById(R.id.coverWidgetFallbackTitle)
        val unavailable: TextView = view.findViewById(R.id.coverWidgetUnavailable)
        val open: View = view.findViewById(R.id.openDashboardWidgetApp)
        var bound: HostedWidget? = null
        var hostView: AppWidgetHostView? = null
        var lastSize: SizeF? = null
        init { container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onLayout(this) } }
    }
}
