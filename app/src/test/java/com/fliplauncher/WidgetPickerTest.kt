package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetPickerTest {
    private lateinit var context: Context
    private lateinit var first: AppWidgetProviderInfo
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(Looper.getMainLooper()).idle()
        context.getSharedPreferences("fliplauncher_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        first = install("weather", "Daily", "Daily forecast", "Weather")
        install("weather", "Hourly", "Hourly forecast", "Weather")
        install("calendar", "Agenda", "Agenda", "Calendar")
    }

    private fun install(app: String, name: String, label: String, appLabel: String): AppWidgetProviderInfo {
        val pkg = "test.widgets.$app"
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(pkg, "$pkg.$name")
            this.label = label
            widgetCategory = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
        }
        ReflectionHelpers.setField(info, "providerInfo", ActivityInfo().apply {
            packageName = pkg; this.name = info.provider.className; nonLocalizedLabel = label
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg; nonLocalizedLabel = appLabel; uid = Process.myUid()
            }
        })
        shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info)
        return info
    }
    private fun adapter(activity: WidgetPickerActivity) =
        activity.findViewById<RecyclerView>(R.id.widgetProviderList).adapter as WidgetPickerAdapter

    private fun await(activity: WidgetPickerActivity, predicate: (List<WidgetPickerRow>) -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(adapter(activity).currentList)) return
            Thread.sleep(10)
        }
        fail("Widget picker did not reach expected state: ${adapter(activity).currentList}")
    }
    private fun click(activity: WidgetPickerActivity, key: String) {
        val list = activity.findViewById<RecyclerView>(R.id.widgetProviderList)
        val adapter = adapter(activity)
        val position = adapter.currentList.indexOfFirst { it.key == key }
        assertTrue("Missing row $key", position >= 0)
        val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
    }

    @Test fun appSearchIncludesAllVariantsButWidgetSearchIncludesOnlyMatchingVariants() {
        val entries = WidgetProviderCatalog.load(context)
        val app = WidgetPickerModel.groups(entries, "weather", null).single()
        assertEquals(2, app.widgets.size)
        assertEquals(listOf("Hourly forecast"), WidgetPickerModel.groups(entries, "hourly", null).single().widgets.map { it.label })
        assertTrue(WidgetPickerModel.groups(entries, "weather", "test.widgets.calendar").isEmpty())
        val duplicateLabel = entries.first().copy(appLabel = "Weather")
        assertEquals(2, WidgetPickerModel.groups(entries.filter { it.appLabel == "Weather" } + duplicateLabel, "", null).size)
    }

    @Test fun groupSelectionReturnsTheExactProviderWithoutAllocatingAWidget() {
        val controller = Robolectric.buildActivity(WidgetPickerActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            await(activity) { it.size == 2 }
            assertTrue(adapter(activity).currentList.all { it is WidgetPickerRow.Header })
            click(activity, "app:test.widgets.weather")
            await(activity) { it.size == 4 }
            val key = "widget:${first.provider.flattenToString()}"
            val stableId = adapter(activity).getItemId(adapter(activity).currentList.indexOfFirst { it.key == key })
            activity.findViewById<EditText>(R.id.widgetSearch).setText("daily")
            await(activity) { it.size == 2 && it.last() is WidgetPickerRow.Provider }
            assertEquals(stableId, adapter(activity).getItemId(1))
            click(activity, key)
            assertEquals(-1, shadowOf(activity).resultCode)
            assertEquals(first.provider.flattenToString(), shadowOf(activity).resultIntent.getStringExtra(WidgetPickerActivity.EXTRA_PROVIDER))
            assertTrue(WidgetScreenStore.ids(context).isEmpty())
            assertNull(WidgetScreenStore.pending(context))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun searchExpansionAndPackageFilterSurviveRecreation() {
        val intent = Intent(context, WidgetPickerActivity::class.java).putExtra(WidgetPickerActivity.EXTRA_PACKAGE, "test.widgets.weather")
        val first = Robolectric.buildActivity(WidgetPickerActivity::class.java, intent).create().start().resume()
        await(first.get()) { it.size == 3 }
        first.get().findViewById<EditText>(R.id.widgetSearch).setText("forecast")
        await(first.get()) { it.size == 3 && (it.first() as WidgetPickerRow.Header).canToggle.not() }
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        val second = Robolectric.buildActivity(WidgetPickerActivity::class.java, intent).create(state).start().resume()
        try {
            await(second.get()) { it.size == 3 }
            assertEquals("forecast", second.get().findViewById<EditText>(R.id.widgetSearch).text.toString())
            second.get().findViewById<EditText>(R.id.widgetSearch).setText("")
            await(second.get()) { it.size == 3 && (it.first() as WidgetPickerRow.Header).canToggle }
            second.get().findViewById<View>(R.id.showAllWidgetApps).performClick()
            await(second.get()) { it.size == 4 }
            assertEquals(View.GONE, second.get().findViewById<View>(R.id.showAllWidgetApps).visibility)
        } finally { second.pause().stop().destroy() }
    }

    @Test fun liveProviderChangesRefreshWithoutLosingQueryAndResumeFindsNewApps() {
        val controller = Robolectric.buildActivity(WidgetPickerActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            await(activity) { it.size == 2 }
            activity.findViewById<EditText>(R.id.widgetSearch).setText("weather")
            await(activity) { it.size == 3 }
            first.widgetFeatures = AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER
            context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:test.widgets.weather")))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            await(activity) { it.size == 2 && (it.last() as WidgetPickerRow.Provider).entry.label == "Hourly forecast" }
            assertEquals("weather", activity.findViewById<EditText>(R.id.widgetSearch).text.toString())
            controller.pause().stop()
            install("music", "Player", "Player", "Music")
            controller.start().resume()
            activity.findViewById<EditText>(R.id.widgetSearch).setText("")
            await(activity) { it.size == 3 }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun editorShortcutMenuOpensAnAppFilteredPicker() {
        val app = ShortcutTestApps.install(context, "weather", "Weather")
        AppShortcutStore.save(context, listOf(AppShortcut(ShortcutTestApps.component(app), "Weather")))
        val controller = Robolectric.buildActivity(WidgetDashboardEditorActivity::class.java).create().start().resume()
        try {
            val list = controller.get().findViewById<RecyclerView>(R.id.editorAppShortcuts)
            val adapter = list.adapter as AppShortcutEditorAdapter
            val holder = adapter.createViewHolder(list, 0)
            adapter.bindViewHolder(holder, 0)
            holder.itemView.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val menu = dialog.listView
            val index = (0 until menu.adapter.count).first { menu.adapter.getItem(it) == context.getString(R.string.widgets_from_app) }
            menu.performItemClick(menu.adapter.getView(index, null, menu), index, menu.adapter.getItemId(index))
            val intent = shadowOf(controller.get()).nextStartedActivityForResult.intent
            assertEquals(ComponentName(context, WidgetPickerActivity::class.java), intent.component)
            assertEquals(app.activityInfo.packageName, intent.getStringExtra(WidgetPickerActivity.EXTRA_PACKAGE))
            assertNull(WidgetScreenStore.pending(context))
        } finally { controller.pause().stop().destroy() }
    }
}
