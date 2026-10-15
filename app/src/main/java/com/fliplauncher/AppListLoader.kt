package com.fliplauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo

/**
 * Loads the list of launchable apps, mirroring the concept from AOSP Launcher3's
 * AppFilter/loader pipeline (see third_party/Launcher3) but kept minimal for the
 * cover display: no widgets, no shortcuts, just the app grid.
 */
object AppListLoader {

    fun load(context: Context): List<AppInfo> {
        val pm: PackageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { toApp(context, it) }
            .distinctBy { it.component }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label.toString() })
    }

    /** Widget updates and launches only load the selected activities, not every installed icon. */
    fun resolve(context: Context, flattenedComponent: String): AppInfo? {
        val component = ComponentName.unflattenFromString(flattenedComponent) ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(component.packageName)
        return runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { ComponentName(it.activityInfo.packageName, it.activityInfo.name) == component }
                ?.let { toApp(context, it) }
        }.getOrNull()
    }

    /**
     * The app a widget provider package opens. Some providers hide their launcher icon (Samsung
     * Weather disables its launcher activity), so fall back to another exported, enabled MAIN
     * entry in the same package. The lookup never leaves the provider's own package.
     */
    fun resolveForPackage(context: Context, packageName: String): AppInfo? {
        context.packageManager.getLaunchIntentForPackage(packageName)?.component
            ?.let { resolve(context, it.flattenToString()) }?.let { return it }
        return runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(packageName), 0)
                .filter { it.activityInfo.packageName == packageName && it.activityInfo.permission == null }
                .firstNotNullOfOrNull { toApp(context, it) }
        }.getOrNull()
    }

    private fun toApp(context: Context, resolved: ResolveInfo): AppInfo? = runCatching {
        val activity = resolved.activityInfo
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
            activity.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
            activity.packageName == context.packageName) return null
        AppInfo(resolved.loadLabel(context.packageManager),
            ComponentName(activity.packageName, activity.name), resolved.loadIcon(context.packageManager))
    }.getOrNull()
}
