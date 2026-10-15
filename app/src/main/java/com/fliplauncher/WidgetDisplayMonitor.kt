package com.fliplauncher

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display

/** Debounces display events; the owner decides whether its widget listeners need rebuilding. */
internal class WidgetDisplayMonitor(context: Context, private val handler: Handler, onChanged: () -> Unit) {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private var closed = false
    private val refresh = Runnable { if (!closed) onChanged() }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed(displayId)
        override fun onDisplayRemoved(displayId: Int) = changed(displayId)
        override fun onDisplayChanged(displayId: Int) = changed(displayId)
    }

    init { manager?.registerDisplayListener(listener, handler) }

    private fun changed(id: Int) {
        if (id == Display.DEFAULT_DISPLAY || id == DisplayHelper.FLIP5_COVER_DISPLAY_ID) configurationChanged()
    }

    fun configurationChanged() {
        if (closed) return
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 150)
    }

    fun close() {
        closed = true
        manager?.unregisterDisplayListener(listener)
        handler.removeCallbacks(refresh)
    }
}

internal data class WidgetDisplayEnvironment(
    val displayId: Int?, val width: Int, val height: Int, val density: Int,
    val configuration: Configuration
) {
    companion object {
        fun read(context: Context): WidgetDisplayEnvironment {
            val metrics = context.resources.displayMetrics
            return WidgetDisplayEnvironment(runCatching { context.display?.displayId }.getOrNull(),
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                Configuration(context.resources.configuration))
        }
    }
}
