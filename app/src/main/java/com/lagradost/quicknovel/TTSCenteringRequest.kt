package com.lagradost.quicknovel

/** Keep an unfinished speech target across visibility changes, without replaying completed targets. */
internal class TTSCenteringRequest {
    private val starts = TTSSentenceStartCursor()
    var sentence: TTSHelper.TTSLine? = null
        private set
    private var pending = false
    private var visible = false
    private var touching = false

    val canRun: Boolean get() = pending && visible && !touching

    fun onSentenceStarted(start: TTSSentenceStart?): Boolean {
        sentence = starts.take(start) ?: return false
        pending = !touching
        return true
    }

    fun onVisibilityChanged(visible: Boolean) {
        this.visible = visible
        touching = false
    }

    fun onTouchStarted() {
        touching = true
        cancel()
    }

    fun onTouchEnded() {
        touching = false
    }

    fun cancel(clearTouch: Boolean = false) {
        pending = false
        if (clearTouch) touching = false
    }

    fun complete() {
        pending = false
    }
}
