package com.fliplauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable

/** A launchable app shown on the cover grid. */
data class AppInfo(
    val label: CharSequence,
    val component: ComponentName,
    val icon: Drawable
)
