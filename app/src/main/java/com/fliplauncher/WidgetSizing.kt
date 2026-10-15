package com.fliplauncher

import android.appwidget.AppWidgetProviderInfo
import android.graphics.Rect
import android.util.SizeF
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Provider dimensions are pixels; both surfaces use these rules before reporting size hints. */
internal object WidgetSizing {
    data class Layout(val width: Int, val height: Int, val fits: Boolean) {
        fun dp(density: Float) = SizeF(width / density, height / density)
    }

    fun calculate(
        info: AppWidgetProviderInfo, size: WidgetSize,
        width: Int, height: Int, density: Float,
        padding: Rect = Rect(), scrolling: Boolean = false
    ): Layout {
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val padX = padding.left + padding.right
        val padY = padding.top + padding.bottom
        fun minimum(default: Int, resize: Int, enabled: Boolean) =
            if (enabled && resize in 1..default) resize else default.coerceAtLeast(1)
        val minWidth = minimum(info.minWidth, info.minResizeWidth, horizontal) + padX
        val minHeight = maxOf(minimum(info.minHeight, info.minResizeHeight, vertical),
            ceil(48 * density).toInt()) + padY
        val heightLimit = if (scrolling) ceil(1200 * density).toInt() else height.coerceAtLeast(1)
        // AUTO keeps the provider's declared default height, as on a phone home screen.
        val fraction = size.fraction
        var desiredHeight = if (!vertical) minHeight
            else if (fraction == null) maxOf(minHeight, info.minHeight + padY)
            else maxOf(minHeight, ((height - padY).coerceAtLeast(0) * fraction).roundToInt() + padY)
        if (vertical && info.maxResizeHeight > 0 && info.maxResizeHeight >= info.minHeight)
            desiredHeight = minOf(desiredHeight, maxOf(minHeight, info.maxResizeHeight + padY))
        var desiredWidth = width.coerceAtLeast(1)
        if (horizontal && info.maxResizeWidth > 0 && info.maxResizeWidth >= info.minWidth)
            desiredWidth = minOf(desiredWidth, info.maxResizeWidth + padX)
        return Layout(desiredWidth, desiredHeight.coerceIn(1, heightLimit),
            desiredWidth >= minWidth && desiredHeight <= heightLimit && width > 0 && height > 0)
    }
}
