package com.fliplauncher

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/** A draft selection: only Save changes the shared cover shortcuts. */
class AppShortcutPickerActivity : AppCompatActivity() {
    private val loader = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AppPickerAdapter
    private var apps = emptyList<AppInfo>()
    private var baseline = emptyList<AppShortcut>()
    private val selected = mutableListOf<String>()
    private var loaded = false
    private var failed = false
    private var loadGeneration = 0
    private var catalogStale = true
    private val catalogChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppShortcutStore.CATALOG_REVISION && !isDestroyed && !isFinishing) loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        baseline = AppShortcutStore.load(this)
        selected.addAll(savedInstanceState?.getStringArrayList("selected_shortcuts") ?: baseline.map { it.component })
        setContentView(R.layout.activity_app_shortcut_picker)
        applyEditorInsets(findViewById(R.id.appShortcutPickerRoot))
        findViewById<TextView>(R.id.shortcutPickerSubtitle).text = getString(R.string.shortcut_picker_subtitle, AppShortcutStore.MAX_SHORTCUTS)
        adapter = AppPickerAdapter { app ->
            val component = app.component.flattenToString()
            if (component in selected) selected.remove(component)
            else if (selected.size < AppShortcutStore.MAX_SHORTCUTS) selected.add(component)
            else Toast.makeText(this, getString(R.string.selection_limit, AppShortcutStore.MAX_SHORTCUTS), Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<RecyclerView>(R.id.shortcutPickerApps).apply {
            layoutManager = GridLayoutManager(this@AppShortcutPickerActivity, 3)
            adapter = this@AppShortcutPickerActivity.adapter
        }
        findViewById<View>(R.id.cancelShortcutPicker).setOnClickListener { finish() }
        findViewById<View>(R.id.saveAppShortcuts).setOnClickListener {
            if (!loaded) return@setOnClickListener
            val labels = baseline.associate { it.component to it.label } +
                apps.associate { it.component.flattenToString() to it.label.toString() }
            AppShortcutStore.save(this, selected.map { AppShortcut(it, labels[it].orEmpty()) })
            setResult(RESULT_OK, Intent())
            finish()
        }
        findViewById<EditText>(R.id.shortcutPickerSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
        })
        render()
    }

    // The revision listener only runs while started, so reload once after a stop that may have missed one.
    override fun onStart() {
        super.onStart()
        AppShortcutStore.preferences(this).registerOnSharedPreferenceChangeListener(catalogChanges)
        if (catalogStale) loadApps()
    }
    override fun onStop() {
        AppShortcutStore.preferences(this).unregisterOnSharedPreferenceChangeListener(catalogChanges)
        catalogStale = true
        super.onStop()
    }
    /** A reload keeps the current list and Save enabled; only the first load shows the loading state. */
    private fun loadApps() {
        val generation = ++loadGeneration
        catalogStale = false
        failed = false; render()
        loader.execute {
            val result = runCatching { AppListLoader.load(applicationContext) }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { apps = it; loaded = true }.onFailure { failed = true }
                render()
            }
        }
    }
    private fun render() {
        if (!::adapter.isInitialized) return
        val query = findViewById<EditText>(R.id.shortcutPickerSearch).text.toString().trim()
        val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
        adapter.submit(filtered, selected.toSet())
        findViewById<TextView>(R.id.shortcutPickerCount).text = getString(R.string.selection_count, selected.size, AppShortcutStore.MAX_SHORTCUTS)
        findViewById<TextView>(R.id.shortcutPickerStatus).apply {
            visibility = if (!loaded || filtered.isEmpty()) View.VISIBLE else View.GONE
            setText(when { failed -> R.string.apps_load_failed; !loaded -> R.string.loading_apps; else -> R.string.no_search_results })
        }
        findViewById<MaterialButton>(R.id.saveAppShortcuts).isEnabled = loaded
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("selected_shortcuts", ArrayList(selected))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }
}
