package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

/** Phone-side editor, also used for standard Android widget configuration. */
class LauncherWidgetConfigActivity : AppCompatActivity() {
    private val loader = Executors.newSingleThreadExecutor()
    private var loading = false
    private var loaded = false
    private var profileId = WidgetSelectionStore.DEFAULT_PROFILE
    private var profileIds = emptyList<Int>()
    private var allApps = emptyList<AppInfo>()
    private val components = mutableListOf<String>()
    private var transparent = true
    private var baseline = WidgetSelection(emptyList())
    private lateinit var picker: AppPickerAdapter
    private lateinit var selected: SelectedAppsAdapter
    private lateinit var search: EditText
    private lateinit var profileSpinner: Spinner
    private lateinit var selectedGrid: RecyclerView
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back/cancel must leave the host's allocated widget unconfigured.
        setResult(RESULT_CANCELED)
        val requested = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            WidgetSelectionStore.DEFAULT_PROFILE)
        val configuration = intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
        if ((configuration && requested == WidgetSelectionStore.DEFAULT_PROFILE) ||
            (requested != WidgetSelectionStore.DEFAULT_PROFILE &&
                !FlipLauncherWidgetProvider.ownsWidget(this, requested))) {
            invalidWidget()
            return
        }
        val widgets = FlipLauncherWidgetProvider.widgetIds(this).sorted()
        profileIds = listOf(WidgetSelectionStore.DEFAULT_PROFILE) + widgets
        profileId = savedInstanceState?.getInt(STATE_PROFILE)
            ?: if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) requested
            else widgets.firstOrNull() ?: WidgetSelectionStore.DEFAULT_PROFILE
        if (profileId !in profileIds) {
            invalidWidget()
            return
        }
        baseline = WidgetSelectionStore.load(this, profileId)
        components.addAll(WidgetSelectionStore.normalize(
            savedInstanceState?.getStringArrayList(STATE_APPS) ?: baseline.components))
        transparent = savedInstanceState?.getBoolean(STATE_TRANSPARENT) ?: baseline.transparent

        setContentView(R.layout.activity_widget_config)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        saveButton = findViewById(R.id.saveSelection)
        saveButton.setText(if (configuration) R.string.save_widget else R.string.save_selection)
        saveButton.setOnClickListener {
            if (persist()) {
                if (profileId != WidgetSelectionStore.DEFAULT_PROFILE) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, profileId))
                }
                Toast.makeText(this, if (profileId == WidgetSelectionStore.DEFAULT_PROFILE)
                    R.string.saved_defaults else R.string.saved_selection, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        findViewById<View>(R.id.setupHelp).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.add_cover_widget)
                .setMessage(R.string.setup_steps)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }.setNegativeButton(R.string.dialog_close, null).show()
        }
        profileSpinner = findViewById(R.id.profileSpinner)
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            profileIds.mapIndexed { index, _ -> if (index == 0) getString(R.string.default_profile)
                else getString(R.string.widget_profile, index) })
        profileSpinner.setSelection(profileIds.indexOf(profileId))
        profileSpinner.visibility = if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) || configuration)
            View.GONE else View.VISIBLE
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = profileIds[position]
                if (next == profileId) return
                if (loaded && currentSelection() != baseline) {
                    profileSpinner.setSelection(profileIds.indexOf(profileId))
                    MaterialAlertDialogBuilder(this@LauncherWidgetConfigActivity)
                        .setTitle(R.string.unsaved_changes).setMessage(R.string.unsaved_changes_summary)
                        .setPositiveButton(R.string.save_selection) { _, _ -> if (persist()) switchProfile(next) }
                        .setNegativeButton(R.string.discard_changes) { _, _ -> switchProfile(next) }
                        .setNeutralButton(R.string.cancel, null).show()
                } else switchProfile(next)
            }
        }

        selected = SelectedAppsAdapter(onRemove = ::removeApp, onManage = ::manageApp)
        selectedGrid = findViewById<RecyclerView>(R.id.selectedAppsGrid).apply {
            layoutManager = GridLayoutManager(this@LauncherWidgetConfigActivity, 4)
            adapter = selected
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in components.indices || to !in components.indices) return false
                components.add(to, components.removeAt(from))
                selected.move(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(selectedGrid)

        picker = AppPickerAdapter(::toggleApp)
        findViewById<RecyclerView>(R.id.pickerAppsGrid).apply {
            layoutManager = GridLayoutManager(this@LauncherWidgetConfigActivity, 4)
            adapter = picker
            isNestedScrollingEnabled = false
        }
        search = findViewById(R.id.pickerSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderPicker()
        })
        findViewById<View>(R.id.previewAdd).setOnClickListener {
            search.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<MaterialSwitch>(R.id.wallpaperSwitch).apply {
            isChecked = transparent
            setOnCheckedChangeListener { _, checked -> transparent = checked; renderBackground() }
        }
        renderSelection()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::picker.isInitialized || loading) return
        loading = true
        loader.execute {
            val result = runCatching { AppListLoader.load(applicationContext) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading = false
                result.onSuccess { apps ->
                    allApps = apps
                    val available = apps.map { it.component.flattenToString() }.toSet()
                    components.retainAll(available)
                    baseline = baseline.copy(components = baseline.components.filter { it in available })
                    loaded = true
                    saveButton.isEnabled = true
                    renderSelection()
                }.onFailure {
                    findViewById<TextView>(R.id.pickerStatus).setText(R.string.apps_load_failed)
                    findViewById<View>(R.id.pickerStatus).visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PROFILE, profileId)
        outState.putStringArrayList(STATE_APPS, ArrayList(components))
        outState.putBoolean(STATE_TRANSPARENT, transparent)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun currentSelection() = WidgetSelection(components.toList(), transparent)

    private fun persist(): Boolean {
        if (!loaded) return false
        if (profileId != WidgetSelectionStore.DEFAULT_PROFILE &&
            !FlipLauncherWidgetProvider.ownsWidget(this, profileId)) {
            invalidWidget()
            return false
        }
        WidgetSelectionStore.save(this, profileId, currentSelection())
        baseline = currentSelection()
        FlipLauncherWidgetProvider.refresh(this, if (profileId == WidgetSelectionStore.DEFAULT_PROFILE)
            FlipLauncherWidgetProvider.widgetIds(this) else intArrayOf(profileId))
        return true
    }

    private fun switchProfile(next: Int) {
        profileId = next
        baseline = WidgetSelectionStore.load(this, profileId)
        components.clear()
        val available = allApps.map { it.component.flattenToString() }.toSet()
        if (loaded) baseline = baseline.copy(components = baseline.components.filter { it in available })
        components.addAll(baseline.components.filter { !loaded || it in available })
        transparent = baseline.transparent
        findViewById<MaterialSwitch>(R.id.wallpaperSwitch).isChecked = transparent
        profileSpinner.setSelection(profileIds.indexOf(next))
        renderSelection()
    }

    private fun toggleApp(app: AppInfo) {
        val component = app.component.flattenToString()
        if (component in components) components.remove(component)
        else if (components.size < WidgetSelectionStore.MAX_APPS) components.add(component)
        else Toast.makeText(this, getString(R.string.selection_limit, WidgetSelectionStore.MAX_APPS),
            Toast.LENGTH_SHORT).show()
        renderSelection()
    }

    private fun removeApp(app: AppInfo) {
        components.remove(app.component.flattenToString())
        renderSelection()
    }

    private fun manageApp(app: AppInfo) {
        val component = app.component.flattenToString()
        val position = components.indexOf(component)
        if (position < 0) return
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (position > 0) actions += R.string.move_earlier to { moveApp(component, -1) }
        if (position < components.lastIndex) actions += R.string.move_later to { moveApp(component, 1) }
        actions += R.string.remove_from_widget to { removeApp(app) }
        MaterialAlertDialogBuilder(this).setTitle(app.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun moveApp(component: String, offset: Int) {
        val from = components.indexOf(component)
        val to = from + offset
        if (from !in components.indices || to !in components.indices) return
        components.add(to, components.removeAt(from))
        renderSelection()
    }

    private fun renderSelection() {
        findViewById<TextView>(R.id.profileHint).setText(if (profileId == WidgetSelectionStore.DEFAULT_PROFILE)
            R.string.default_profile_hint else R.string.widget_profile_hint)
        findViewById<TextView>(R.id.selectionCount).text = getString(R.string.selection_count,
            components.size, WidgetSelectionStore.MAX_APPS)
        val byComponent = allApps.associateBy { it.component.flattenToString() }
        val apps = components.mapNotNull(byComponent::get)
        selected.submit(apps)
        selectedGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        selectedGrid.layoutParams = selectedGrid.layoutParams.apply {
            height = (minOf(3, (apps.size + 3) / 4) * 80 * resources.displayMetrics.density).toInt()
        }
        findViewById<View>(R.id.previewEmpty).visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        renderBackground()
        renderPicker()
    }

    private fun renderBackground() {
        findViewById<View>(R.id.previewSurface).setBackgroundResource(
            if (transparent) R.drawable.fl_preview_wallpaper else R.drawable.fl_widget_background)
    }

    private fun renderPicker() {
        if (!::picker.isInitialized || !::search.isInitialized) return
        val query = search.text.toString().trim()
        val filtered = allApps.filter { it.label.toString().contains(query, ignoreCase = true) }
        picker.submit(filtered, components.toSet())
        findViewById<TextView>(R.id.pickerStatus).apply {
            visibility = if (!loaded || filtered.isEmpty()) View.VISIBLE else View.GONE
            setText(if (loaded) R.string.no_search_results else R.string.loading_apps)
        }
    }

    private fun invalidWidget() {
        Toast.makeText(this, R.string.invalid_selection, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val STATE_PROFILE = "profile"
        private const val STATE_APPS = "apps"
        private const val STATE_TRANSPARENT = "transparent"
    }
}
