package com.fliplauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display

/** Samsung's Flip5 sample identifies the cover as display 1. Never select a cast display. */
object DisplayHelper {
    const val FLIP5_COVER_DISPLAY_ID = 1

    fun findCoverDisplay(context: Context): Display? {
        // A private cover display may be hidden from the provider's application context.
        // Once the relay is running there, use its actual attached display first.
        val attached = runCatching { context.display }.getOrNull()
        if (attached?.displayId == FLIP5_COVER_DISPLAY_ID && attached.isValid) return attached
        return context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(FLIP5_COVER_DISPLAY_ID)?.takeIf { it.isValid }
    }

    fun attachedDisplayId(context: Context): Int? = runCatching { context.display?.displayId }.getOrNull()

    fun isOnCover(activity: Activity): Boolean = attachedDisplayId(activity) == FLIP5_COVER_DISPLAY_ID

    fun activityOptions(displayId: Int, forPendingIntent: Boolean = false): Bundle {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (forPendingIntent && Build.VERSION.SDK_INT >= 35) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        return options.toBundle()
    }

    /** The system creates the configuration sender; the visible editor sends it. */
    fun widgetConfigurationOptions(): Bundle {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        return options.toBundle()
    }
}
