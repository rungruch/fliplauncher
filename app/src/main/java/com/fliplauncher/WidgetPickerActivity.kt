package com.fliplauncher

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.Future

class WidgetPickerActivity : AppCompatActivity() {
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var previews: WidgetPreviewLoader
    private lateinit var adapter: WidgetPickerAdapter
    private lateinit var monitor: WidgetCatalogMonitor
    private var entries = emptyList<WidgetProviderEntry>()
    private var loaded = false
    private var catalogStale = true
    private var failed = false
    private var loadGeneration = 0
    private var pendingLoad: Future<*>? = null
    private var packageFilter: String? = null
    private val expanded = mutableSetOf<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        packageFilter = if (savedInstanceState != null) savedInstanceState.getString("package_filter")
            else intent.getStringExtra(EXTRA_PACKAGE)
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded_apps")
            ?: listOfNotNull(packageFilter))
        setContentView(R.layout.activity_widget_picker)
        applyEditorInsets(findViewById(R.id.widgetPickerRoot))
        previews = WidgetPreviewLoader(this)
        monitor = WidgetCatalogMonitor(this, ::loadWidgets)
        adapter = WidgetPickerAdapter(previews, { pkg ->
            if (!expanded.add(pkg)) expanded.remove(pkg)
            render()
        }) { entry ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PROVIDER, entry.info.provider.flattenToString()))
            finish()
        }
        findViewById<RecyclerView>(R.id.widgetProviderList).apply {
            layoutManager = LinearLayoutManager(this@WidgetPickerActivity)
            adapter = this@WidgetPickerActivity.adapter
        }
        findViewById<View>(R.id.closeWidgetPicker).setOnClickListener { finish() }
        findViewById<View>(R.id.showAllWidgetApps).setOnClickListener { packageFilter = null; render() }
        findViewById<EditText>(R.id.widgetSearch).setText(savedInstanceState?.getString("widget_query").orEmpty())
        findViewById<EditText>(R.id.widgetSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
        })
        render()
    }
    // The monitor only listens while started, so reload once after any stop that may have missed changes.
    override fun onStart() { super.onStart(); monitor.start(); if (catalogStale) loadWidgets() }
    override fun onStop() { monitor.stop(); catalogStale = true; super.onStop() }

    private fun loadWidgets() {
        val generation = ++loadGeneration
        catalogStale = false
        failed = false
        render()
        // Invalidation drops in-flight previews; the completed load rebinds rows so they request again.
        previews.invalidate()
        pendingLoad?.cancel(true)
        pendingLoad = loader.submit {
            val result = runCatching { WidgetProviderCatalog.load(applicationContext) }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { entries = it; loaded = true }.onFailure { failed = true }
                render(rebindPreviews = true)
            }
        }
    }
    private fun render(rebindPreviews: Boolean = false) {
        val query = findViewById<EditText>(R.id.widgetSearch).text.toString().trim()
        val groups = WidgetPickerModel.groups(entries, query, packageFilter)
        adapter.submitList(WidgetPickerModel.rows(groups, expanded, query.isNotBlank())) {
            if (rebindPreviews) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
        findViewById<View>(R.id.showAllWidgetApps).visibility = if (packageFilter == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.widgetPickerSubtitle).text = packageFilter?.let { pkg ->
            val label = entries.firstOrNull { it.info.provider.packageName == pkg }?.appLabel
                ?: runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)
            getString(R.string.widgets_for_app, label)
        } ?: getString(R.string.widget_picker_subtitle)
        findViewById<TextView>(R.id.widgetPickerStatus).apply {
            visibility = if (failed || !loaded || groups.isEmpty()) View.VISIBLE else View.GONE
            setText(when {
                failed -> R.string.widgets_load_failed
                !loaded -> R.string.loading_widgets
                packageFilter != null && groups.isEmpty() -> R.string.no_widgets_for_app
                else -> R.string.no_widget_results
            })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("package_filter", packageFilter)
        outState.putStringArrayList("expanded_apps", ArrayList(expanded))
        outState.putString("widget_query", findViewById<EditText>(R.id.widgetSearch).text.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        ++loadGeneration
        pendingLoad?.cancel(true)
        loader.shutdownNow()
        if (::previews.isInitialized) previews.close()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_PROVIDER = "com.fliplauncher.WIDGET_PROVIDER"
        const val EXTRA_PACKAGE = "com.fliplauncher.WIDGET_PACKAGE"
    }
}

fun AppCompatActivity.applyEditorInsets(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        insets
    }
}
