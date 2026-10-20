package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.TextVisualLine

/** Coordinates share the window origin; toolbars and floating panels do not shift screen center. */
internal fun readerLineAtScreenCenter(
    lines: List<TextVisualLine>,
    screenCenter: Int,
    viewportTop: Int,
    viewportBottom: Int,
): TextVisualLine? {
    if (viewportBottom <= viewportTop) return null
    val visible = lines.filter { it.bottom > it.top && it.bottom > viewportTop && it.top < viewportBottom }
    val target = screenCenter.coerceIn(viewportTop, viewportBottom)
    return visible.firstOrNull { it.top <= target && it.bottom > target }
        ?: visible.minWithOrNull(
            compareBy<TextVisualLine> {
                    when {
                        target < it.top -> it.top - target
                        else -> target - it.bottom
                    }
                }
                .thenByDescending { it.top }
        )
}
