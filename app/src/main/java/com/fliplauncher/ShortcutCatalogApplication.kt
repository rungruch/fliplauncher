package com.fliplauncher

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle

/** Reconcile on process start and coalesce live catalog events without a background service. */
class ShortcutCatalogApplication : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { AppShortcutStore.catalogChanged(this) }
    private fun changed(user: UserHandle) {
        if (user != Process.myUserHandle()) return
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 250)
    }
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = changed(user)
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = changed(user)
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(LauncherApps::class.java)?.registerCallback(callback, handler)
        handler.post { AppShortcutStore.catalogChanged(this, catalogEvent = false) }
    }
}
