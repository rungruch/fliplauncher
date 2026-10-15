package com.fliplauncher

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HostedWidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED ||
            intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetScreenStore.HOST_ID) return
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        if (oldIds.size != newIds.size) return
        WidgetScreenStore.remap(context, oldIds, newIds)
        oldIds.forEach { NativeWidgetRelay.forget(context, it) }
        NativeRelayRuntime.boardChanged()
        FlipLauncherWidgetProvider.refresh(context)
    }
}
