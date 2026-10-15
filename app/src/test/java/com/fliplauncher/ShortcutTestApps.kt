package com.fliplauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.robolectric.Shadows.shadowOf

internal object ShortcutTestApps {
    fun install(context: Context, name: String, label: String): ResolveInfo {
        val packageName = "test.shortcut.$name"
        val info = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "$packageName.Main"
                exported = true; enabled = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
            }
        }
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(intent(), info)
        pm.addResolveInfoForIntent(intent().setPackage(packageName), info)
        return info
    }
    fun component(info: ResolveInfo) = ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString()
    fun remove(context: Context, info: ResolveInfo) {
        val packageName = info.activityInfo.packageName
        shadowOf(context.packageManager).removeResolveInfosForIntent(intent(), packageName)
        shadowOf(context.packageManager).removeResolveInfosForIntent(intent().setPackage(packageName), packageName)
    }
    private fun intent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
