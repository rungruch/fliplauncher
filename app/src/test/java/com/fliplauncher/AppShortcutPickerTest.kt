package com.fliplauncher

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppShortcutPickerTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(Looper.getMainLooper()).idle()
        AppShortcutStore.preferences(context).edit().clear().commit()
        ShortcutTestApps.install(context, "one", "One")
        ShortcutTestApps.install(context, "two", "Two")
    }
    private fun awaitApps(activity: AppShortcutPickerActivity) {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.findViewById<View>(R.id.saveAppShortcuts).isEnabled) return
            Thread.sleep(10)
        }
        fail("App picker did not finish loading")
    }
    private fun toggle(activity: AppShortcutPickerActivity, position: Int) {
        val list = activity.findViewById<RecyclerView>(R.id.shortcutPickerApps)
        val adapter = list.adapter as AppPickerAdapter
        val holder = adapter.createViewHolder(list, 0)
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
    }
    @Test fun cancellingTheDraftDoesNotChangeSavedShortcuts() {
        val saved = listOf(AppShortcut("test.shortcut.one/test.shortcut.one.Main", "One"))
        AppShortcutStore.save(context, saved)
        val controller = Robolectric.buildActivity(AppShortcutPickerActivity::class.java).create().start().resume()
        awaitApps(controller.get())
        toggle(controller.get(), 1)
        controller.get().findViewById<View>(R.id.cancelShortcutPicker).performClick()
        assertEquals(saved, AppShortcutStore.load(context))
        controller.pause().stop().destroy()
    }
    @Test fun draftOrderSurvivesRecreationAndOnlySaveCommitsIt() {
        val first = Robolectric.buildActivity(AppShortcutPickerActivity::class.java).create().start().resume()
        awaitApps(first.get())
        toggle(first.get(), 1)
        toggle(first.get(), 0)
        assertTrue(AppShortcutStore.load(context).isEmpty())
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()

        val second = Robolectric.buildActivity(AppShortcutPickerActivity::class.java).create(state).start().resume()
        awaitApps(second.get())
        second.get().findViewById<View>(R.id.saveAppShortcuts).performClick()
        assertEquals(listOf("Two", "One"), AppShortcutStore.load(context).map { it.label })
        second.pause().stop().destroy()
    }
}
