package com.lagradost.quicknovel

/** A press becomes a drag only after touch slop; a drag can never become a click again. */
internal class FloatingControlsGesture(private val touchSlop: Float) {
    private data class Start(val touchX: Float, val touchY: Float, val panelX: Float, val panelY: Float)

    private var start: Start? = null
    val isActive: Boolean
        get() = start != null

    var isDragging = false
        private set

    fun down(touchX: Float, touchY: Float, panelX: Float, panelY: Float) {
        start = Start(touchX, touchY, panelX, panelY)
        isDragging = false
    }

    fun move(touchX: Float, touchY: Float): Pair<Float, Float>? {
        val origin = start ?: return null
        val dx = touchX - origin.touchX
        val dy = touchY - origin.touchY
        if (dx * dx + dy * dy > touchSlop * touchSlop) isDragging = true
        return if (isDragging) origin.panelX + dx to origin.panelY + dy else null
    }

    /** True only for a stationary press released once, regardless of how long it was held. */
    fun up(): Boolean {
        val click = isActive && !isDragging
        cancel()
        return click
    }

    fun cancel() {
        start = null
        isDragging = false
    }
}
