package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class WidgetDashboardEditorActivity : AppCompatActivity() {
    private lateinit var helper: WidgetHelper
    private lateinit var previews: WidgetPreviewLoader
    private lateinit var adapter: WidgetScreenAdapter
    private lateinit var shortcuts: AppShortcutEditorAdapter
    private val setupChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private var shortcutId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var owner = ""
    private var queuedProvider: String? = null
    private var setupLaunchPosted = false
    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        helper.onBindResult(it.resultCode)
    }
    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // Activity-result callbacks can precede onResume. Starting another activity here
            // can leave a provider's setup task behind Samsung Settings or the picker.
            queuedProvider = result.data?.getStringExtra(WidgetPickerActivity.EXTRA_PROVIDER)
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        shortcutId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if ((intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE && shortcutId == AppWidgetManager.INVALID_APPWIDGET_ID) ||
            (shortcutId != AppWidgetManager.INVALID_APPWIDGET_ID && !FlipLauncherWidgetProvider.ownsWidget(this, shortcutId))) {
            showMessage(R.string.invalid_selection); finish(); return
        }
        owner = savedInstanceState?.getString("setup_owner") ?: UUID.randomUUID().toString()
        queuedProvider = savedInstanceState?.getString("queued_provider")
        setContentView(R.layout.activity_widget_dashboard_editor)
        applyEditorInsets(findViewById(R.id.dashboardEditorRoot))
        helper = WidgetHelper(this, owner, { bindLauncher.launch(it) }, ::render, ::showMessage)
        previews = WidgetPreviewLoader(this)
        adapter = WidgetScreenAdapter(previews, ::manageWidget)
        shortcuts = AppShortcutEditorAdapter(::manageShortcut)
        findViewById<RecyclerView>(R.id.editorAppShortcuts).apply {
            layoutManager = LinearLayoutManager(this@WidgetDashboardEditorActivity)
            adapter = shortcuts
        }
        val list = findViewById<RecyclerView>(R.id.widgetScreenList).apply {
            layoutManager = LinearLayoutManager(this@WidgetDashboardEditorActivity)
            adapter = this@WidgetDashboardEditorActivity.adapter
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            private var orderChanged = false
            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder): Boolean {
                val from = holder.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from !in 0 until adapter.itemCount || to !in 0 until adapter.itemCount) return false
                helper.move(from, to, notify = false); adapter.move(from, to); orderChanged = true; return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (orderChanged) {
                    orderChanged = false
                    recyclerView.post { if (!isDestroyed && !isFinishing) helper.publishChanges() }
                }
            }
        }).attachToRecyclerView(list)
        findViewById<View>(R.id.addWidgetScreen).setOnClickListener { addWidget() }
        findViewById<View>(R.id.manageAppShortcuts).setOnClickListener { chooseApps() }
        findViewById<View>(R.id.setupWidgetAction).setOnClickListener { openCoverSettings() }
        findViewById<View>(R.id.setupContentAction).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.setup_choose_content)
                .setItems(arrayOf(getString(R.string.choose_app_shortcuts), getString(R.string.add_widget))) { _, index ->
                    if (index == 0) chooseApps() else addWidget()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        findViewById<View>(R.id.setupLaunchHelp).setOnClickListener { showHelp() }
        findViewById<View>(R.id.dismissLaunchIssue).setOnClickListener { CoverSetupStatusStore.clearIssue(this) }
        findViewById<View>(R.id.cancelWidgetSetup).setOnClickListener { helper.cancelPending(force = true) }
        findViewById<View>(R.id.widgetDashboardHelp).setOnClickListener { showHelp() }
        findViewById<View>(R.id.nativeCoverUpdates).setOnClickListener {
            if (NativeWidgetRelayService.running()) NativeWidgetRelayService.stop(this)
            else NativeWidgetRelayService.startFromEditor(this)
            render()
            findViewById<View>(R.id.nativeCoverUpdates).postDelayed({
                if (!isFinishing && !isDestroyed) render()
            }, 500)
        }
        findViewById<View>(R.id.finishWidgetEditor).setOnClickListener {
            if (shortcutId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                if (!FlipLauncherWidgetProvider.ownsWidget(this, shortcutId)) {
                    showMessage(R.string.invalid_selection); finish(); return@setOnClickListener
                }
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, shortcutId))
            }
            FlipLauncherWidgetProvider.refresh(this)
            finish()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) render()
        NativeWidgetRelayService.autoStart(this)
        requestUnrestrictedBattery()
    }

    /** Background update starts need the battery exemption; ask once, outside any widget setup. */
    private fun requestUnrestrictedBattery() {
        if (FlipLauncherWidgetProvider.widgetIds(this).isEmpty() || WidgetScreenStore.pending(this) != null) return
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("native_widget_relay", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompted", false)) return
        prefs.edit().putBoolean("battery_prompted", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { android.util.Log.w("FlipLauncherNative", "Cannot request battery exemption", it) }
    }

    override fun onStart() {
        super.onStart()
        AppShortcutStore.preferences(this).registerOnSharedPreferenceChangeListener(setupChanges)
        CoverSetupStatusStore.preferences(this).registerOnSharedPreferenceChangeListener(setupChanges)
    }
    override fun onStop() {
        AppShortcutStore.preferences(this).unregisterOnSharedPreferenceChangeListener(setupChanges)
        CoverSetupStatusStore.preferences(this).unregisterOnSharedPreferenceChangeListener(setupChanges)
        super.onStop()
    }

    private fun chooseApps() { startActivity(Intent(this, AppShortcutPickerActivity::class.java)) }
    private fun addWidget(packageName: String? = null) {
        if (WidgetScreenStore.pending(this) != null || queuedProvider != null) {
            showMessage(R.string.finish_pending_setup); return
        }
        if (WidgetScreenStore.ids(this).size >= WidgetScreenStore.MAX_WIDGETS) {
            showMessage(R.string.widget_screen_limit); return
        }
        pickerLauncher.launch(Intent(this, WidgetPickerActivity::class.java).apply {
            if (packageName != null) putExtra(WidgetPickerActivity.EXTRA_PACKAGE, packageName)
        })
    }
    private fun openCoverSettings() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.add_cover_widget)
            .setMessage(R.string.setup_cover_settings_hint)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                catch (_: RuntimeException) { showMessage(R.string.setup_cover_settings_hint) }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    override fun onPostResume() {
        super.onPostResume()
        queueSetupLaunch()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) queueSetupLaunch()
    }

    private fun queueSetupLaunch() {
        if (!::helper.isInitialized || setupLaunchPosted || isFinishing || isDestroyed) return
        setupLaunchPosted = true
        window.decorView.post {
            setupLaunchPosted = false
            if (isFinishing || isDestroyed || !hasWindowFocus() ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@post
            queuedProvider?.let { selected ->
                queuedProvider = null
                val component = ComponentName.unflattenFromString(selected)
                val info = AppWidgetManager.getInstance(this).installedProviders.firstOrNull {
                    it.provider == component && it.provider.packageName != packageName &&
                        it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                }
                if (info != null) helper.startAdd(info) else {
                    showMessage(R.string.widget_unavailable)
                    render()
                }
            }
            helper.launchPendingConfiguration()
        }
    }

    @Deprecated("Widget configuration uses AppWidgetHost's system-mediated result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WidgetHelper.CONFIGURE_REQUEST && ::helper.isInitialized) helper.onConfigureResult(resultCode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("setup_owner", owner)
        outState.putString("queued_provider", queuedProvider)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::previews.isInitialized) previews.close()
        if (::helper.isInitialized) helper.onDestroy()
        super.onDestroy()
    }

    private fun render() {
        if (!::adapter.isInitialized || isFinishing || isDestroyed) return
        val widgets = helper.screens()
        val shortcutEntries = AppShortcutStore.entries(this)
        shortcuts.submit(shortcutEntries)
        findViewById<TextView>(R.id.editorShortcutCount).text = getString(R.string.shortcut_editor_count, shortcuts.itemCount, AppShortcutStore.MAX_SHORTCUTS)
        findViewById<View>(R.id.editorShortcutsEmpty).visibility = if (shortcuts.itemCount == 0) View.VISIBLE else View.GONE
        adapter.submit(widgets)
        findViewById<TextView>(R.id.widgetScreenCount).text = getString(R.string.widget_screen_count, widgets.size)
        findViewById<View>(R.id.widgetDashboardEmpty).visibility = if (widgets.isEmpty()) View.VISIBLE else View.GONE
        val pending = WidgetScreenStore.pending(this)
        renderChecklist(CoverSetupState.read(this, queuedProvider != null, shortcutEntries))
        findViewById<View>(R.id.widgetSetupPending).visibility = if (pending == null) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.addWidgetScreen).isEnabled = pending == null && queuedProvider == null && widgets.size < WidgetScreenStore.MAX_WIDGETS
        findViewById<MaterialButton>(R.id.finishWidgetEditor).isEnabled = pending == null && queuedProvider == null
        queueSetupLaunch()
    }

    private fun renderChecklist(state: CoverSetupState) {
        findViewById<TextView>(R.id.setupWidgetStatus).setText(if (state.widgetAdded) R.string.setup_widget_added else R.string.setup_widget_missing)
        findViewById<TextView>(R.id.setupContentStatus).text = if (state.contentReady)
            getString(R.string.setup_content_ready, state.availableApps, state.availableWidgets) else getString(R.string.setup_content_missing)
        findViewById<TextView>(R.id.setupUpdatesStatus).setText(when (state.updatesState) {
            CoverUpdatesState.RUNNING -> R.string.setup_updates_running
            CoverUpdatesState.STOPPED -> R.string.setup_updates_stopped
            CoverUpdatesState.NEEDS_SURFACE -> R.string.setup_updates_need_widget
            CoverUpdatesState.NOT_NEEDED -> R.string.setup_updates_not_needed
        })
        findViewById<TextView>(R.id.setupUpdatesDetail).setText(if (state.availableWidgets == 0)
            R.string.setup_shortcuts_no_service else R.string.setup_widget_updates_detail)
        findViewById<MaterialButton>(R.id.nativeCoverUpdates).apply {
            setText(when { state.updatesRunning -> R.string.native_stop_updates; state.availableWidgets == 0 -> R.string.setup_not_needed; else -> R.string.native_start_updates })
            isEnabled = state.updatesRunning || state.canStartUpdates
        }
        val issue = CoverSetupStatusStore.lastIssue(this)
        findViewById<View>(R.id.setupLaunchIssue).visibility = if (issue == null) View.GONE else View.VISIBLE
        if (issue != null) {
            findViewById<TextView>(R.id.setupLaunchIssueTitle).text = getString(R.string.setup_last_launch, issue.label)
            findViewById<TextView>(R.id.setupLaunchIssueMessage).setText(issue.issue.message)
        }
    }

    private fun manageShortcut(entry: AppShortcutEntry) {
        val items = AppShortcutStore.load(this)
        val position = items.indexOfFirst { it.component == entry.shortcut.component }
        if (position < 0) return
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        entry.app?.let { app -> actions += R.string.widgets_from_app to { addWidget(app.component.packageName) } }
        if (position > 0) actions += R.string.move_earlier to { moveShortcut(entry.shortcut.component, -1) }
        if (position < items.lastIndex) actions += R.string.move_later to { moveShortcut(entry.shortcut.component, 1) }
        // Saving notifies setupChanges, which re-renders.
        actions += R.string.remove_app_shortcut to {
            AppShortcutStore.save(this, AppShortcutStore.load(this).filter { it.component != entry.shortcut.component })
        }
        MaterialAlertDialogBuilder(this).setTitle(entry.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }
    private fun moveShortcut(component: String, offset: Int) {
        val items = AppShortcutStore.load(this).toMutableList()
        val from = items.indexOfFirst { it.component == component }
        val to = from + offset
        if (from < 0 || to !in items.indices) return
        items.add(to, items.removeAt(from))
        AppShortcutStore.save(this, items)
    }

    private fun manageWidget(widget: HostedWidget) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        val ids = WidgetScreenStore.ids(this); val position = ids.indexOf(widget.id)
        if (widget.info?.resizeMode?.and(AppWidgetProviderInfo.RESIZE_VERTICAL) == AppWidgetProviderInfo.RESIZE_VERTICAL)
            actions += R.string.widget_size to { chooseSize(widget) }
        if (widget.info?.configure != null) actions += R.string.widget_configure to { helper.reconfigure(widget) }
        if (position > 0) actions += R.string.move_earlier to { moveWidget(widget.id, -1) }
        if (position >= 0 && position < ids.lastIndex) actions += R.string.move_later to { moveWidget(widget.id, 1) }
        actions += R.string.remove_widget_screen to {
            MaterialAlertDialogBuilder(this).setTitle(R.string.remove_widget_screen)
                .setMessage(getString(R.string.remove_widget_confirm, widget.label))
                .setPositiveButton(R.string.remove_widget_screen) { _, _ -> helper.remove(widget.id) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        MaterialAlertDialogBuilder(this).setTitle(widget.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseSize(widget: HostedWidget) {
        val sizes = WidgetSize.entries
        MaterialAlertDialogBuilder(this).setTitle(R.string.widget_size)
            .setSingleChoiceItems(sizes.map { getString(it.labelResource()) }.toTypedArray(), sizes.indexOf(widget.size)) { dialog, index ->
                helper.setSize(widget, sizes[index]); dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun moveWidget(id: Int, offset: Int) {
        val from = WidgetScreenStore.ids(this).indexOf(id)
        if (from >= 0) helper.move(from, from + offset)
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.open_widget_screens)
            .setMessage(R.string.widget_dashboard_steps)
            .setPositiveButton(R.string.open_good_lock) { _, _ ->
                val intent = packageManager.getLaunchIntentForPackage("com.samsung.android.goodlock")
                if (intent != null) startActivity(intent) else showMessage(R.string.open_good_lock_manually)
            }.setNegativeButton(R.string.dialog_close, null).show()
    }

    private fun showMessage(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
