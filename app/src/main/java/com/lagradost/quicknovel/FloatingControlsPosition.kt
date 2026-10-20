package com.lagradost.quicknovel

/** Fractions of the available movement range, retained across screen sizes. */
internal data class FloatingControlsPosition(val horizontal: Float = 1f, val vertical: Float = 1f) {
    fun place(bounds: FloatingControlsBounds): Pair<Float, Float> =
        bounds.left + horizontal.fraction() * bounds.width to bounds.top + vertical.fraction() * bounds.height

    companion object {
        fun fromOffset(x: Float, y: Float, bounds: FloatingControlsBounds) =
            FloatingControlsPosition(
                if (bounds.width > 0) ((x - bounds.left) / bounds.width).fraction() else 1f,
                if (bounds.height > 0) ((y - bounds.top) / bounds.height).fraction() else 1f,
            )
    }
}

private fun Float.fraction(): Float = if (isFinite()) coerceIn(0f, 1f) else 1f

/** Allowed panel origins after subtracting its size from the usable reader area. */
internal class FloatingControlsBounds
private constructor(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun constrain(x: Float, y: Float): Pair<Float, Float> =
        x.coerceIn(left, left + width) to y.coerceIn(top, top + height)

    companion object {
        fun forArea(left: Float, top: Float, right: Float, bottom: Float, panelWidth: Float, panelHeight: Float) =
            FloatingControlsBounds(
                left,
                top,
                (right - left - panelWidth).coerceAtLeast(0f),
                (bottom - top - panelHeight).coerceAtLeast(0f),
            )
    }
}
