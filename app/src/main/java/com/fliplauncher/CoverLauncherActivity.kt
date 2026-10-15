package com.fliplauncher

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/** The existing Good Lock launcher component now opens live widget pages on the cover. */
// RemoteViews clones this activity's inflater. AppCompat's factory replaces framework
// ImageView/TextView classes, which can reject a provider's @RemotableViewMethod actions.
// Keep the actual cover activity and its display resources, with a native inflater.
class CoverLauncherActivity : ComponentActivity() {
    private val shortcutChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::helper.isInitialized && !isFinishing && !isDestroyed) bindShortcuts()
    }
    private lateinit var helper: WidgetHelper
    private lateinit var adapter: WidgetPageAdapter
    private lateinit var pages: RecyclerView
    private val snap = PagerSnapHelper()
    private var selectedId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val autoAdvance = object : Runnable {
        override fun run() {
            if (!::helper.isInitialized || isDestroyed || isFinishing || adapter.itemCount == 0) return
            helper.advance(selectedId)
            handler.postDelayed(this, 20_000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DisplayHelper.isOnCover(this)) { openPhoneEditor(); return }
        setContentView(R.layout.activity_cover_widget_screens)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coverWidgetRoot)) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val padding = (8 * resources.displayMetrics.density).toInt()
            view.setPadding(safe.left + padding, safe.top + padding, safe.right + padding, safe.bottom + padding)
            insets
        }
        selectedId = selectedFromIntent(intent) ?: savedInstanceState?.getInt("selected_widget", -1) ?: -1
        helper = WidgetHelper(this, onChanged = ::refreshScreens,
            onMessage = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() })
        adapter = WidgetPageAdapter(helper)
        pages = findViewById<RecyclerView>(R.id.coverWidgetPages).apply {
            layoutManager = LinearLayoutManager(this@CoverLauncherActivity, RecyclerView.HORIZONTAL, false)
            adapter = this@CoverLauncherActivity.adapter
        }
        snap.attachToRecyclerView(pages)
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) renderCounter()
            }
        })
        findViewById<View>(R.id.editWidgetScreens).setOnClickListener { openPhoneEditor() }
        findViewById<View>(R.id.coverWidgetEmpty).setOnClickListener { openPhoneEditor() }
        findViewById<View>(R.id.previousWidgetPage).setOnClickListener { goToPage(-1) }
        findViewById<View>(R.id.nextWidgetPage).setOnClickListener { goToPage(1) }
        refreshScreens()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedFromIntent(intent)?.let {
            selectedId = it
            if (::helper.isInitialized) refreshScreens()
        }
    }
    private fun selectedFromIntent(intent: Intent): Int? =
        intent.getIntExtra(EXTRA_SELECTED_WIDGET, -1).takeIf { it in WidgetScreenStore.ids(this) }
    override fun onPause() { handler.removeCallbacks(autoAdvance); super.onPause() }
    override fun onStart() {
        super.onStart()
        if (::helper.isInitialized) {
            helper.onStart()
            AppShortcutStore.preferences(this).registerOnSharedPreferenceChangeListener(shortcutChanges)
        }
    }
    override fun onStop() {
        AppShortcutStore.preferences(this).unregisterOnSharedPreferenceChangeListener(shortcutChanges)
        if (::helper.isInitialized) helper.onStop()
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!DisplayHelper.isOnCover(this)) { openPhoneEditor(); return }
        refreshScreens()
        handler.removeCallbacks(autoAdvance)
        if (adapter.itemCount > 0) handler.postDelayed(autoAdvance, 20_000)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!DisplayHelper.isOnCover(this) && !isFinishing) openPhoneEditor()
        else if (::helper.isInitialized && !isFinishing) {
            helper.refreshDisplay()
            refreshScreens()
        }
    }
    // The empty-state wording depends on shortcuts, so it is refreshed with the strip.
    private fun bindShortcuts() {
        val hasShortcuts = AppShortcutViews.bindDashboard(this, findViewById<LinearLayout>(R.id.dashboardShortcutStrip))
        findViewById<TextView>(R.id.coverWidgetEmpty).setText(
            if (hasShortcuts) R.string.cover_shortcuts_only else R.string.cover_widgets_empty)
    }

    private fun refreshScreens() {
        if (!::adapter.isInitialized || isDestroyed || isFinishing) return
        if (pages.isComputingLayout) { pages.post { refreshScreens() }; return }
        val widgets = helper.screens()
        bindShortcuts()
        val before = adapter.ids()
        val current = currentPosition()
        if (selectedId < 0) selectedId = before.getOrNull(current) ?: -1
        adapter.submit(widgets)
        val position = widgets.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?: current.coerceIn(0, maxOf(0, widgets.lastIndex))
        if (widgets.isNotEmpty()) { selectedId = widgets[position].id; pages.scrollToPosition(position) }
        findViewById<View>(R.id.coverWidgetEmpty).visibility = if (widgets.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.coverWidgetPages).visibility = if (widgets.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.widgetPageNavigation).visibility = if (widgets.isEmpty()) View.GONE else View.VISIBLE
        pages.post { if (!isDestroyed && !isFinishing) renderCounter() }
    }
    private fun currentPosition(): Int = pages.layoutManager?.let { layout ->
        snap.findSnapView(layout)?.let(layout::getPosition)
    }?.coerceAtLeast(0) ?: 0
    private fun renderCounter() {
        if (adapter.itemCount == 0) return
        val position = currentPosition().coerceIn(0, adapter.itemCount - 1)
        selectedId = adapter.ids()[position]
        findViewById<TextView>(R.id.widgetPageCounter).apply {
            text = getString(R.string.widget_page_number, position + 1, adapter.itemCount)
            contentDescription = getString(R.string.widget_page_accessibility, adapter.labelAt(position), position + 1, adapter.itemCount)
        }
        findViewById<ImageButton>(R.id.previousWidgetPage).apply { isEnabled = position > 0; alpha = if (isEnabled) 1f else 0.3f }
        findViewById<ImageButton>(R.id.nextWidgetPage).apply { isEnabled = position < adapter.itemCount - 1; alpha = if (isEnabled) 1f else 0.3f }
    }
    private fun goToPage(offset: Int) {
        if (adapter.itemCount == 0) return
        pages.smoothScrollToPosition((currentPosition() + offset).coerceIn(0, adapter.itemCount - 1))
    }
    private fun openPhoneEditor() {
        val editor = Intent(this, WidgetDashboardEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(editor, DisplayHelper.activityOptions(Display.DEFAULT_DISPLAY)) }
        catch (_: RuntimeException) { Toast.makeText(this, R.string.unfold_widgets_editor, Toast.LENGTH_LONG).show() }
        if (!DisplayHelper.isOnCover(this)) finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_widget", selectedId); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        handler.removeCallbacks(autoAdvance)
        if (::helper.isInitialized) helper.onDestroy()
        super.onDestroy()
    }
    companion object { const val EXTRA_SELECTED_WIDGET = "com.fliplauncher.SELECTED_HOSTED_WIDGET" }
}
