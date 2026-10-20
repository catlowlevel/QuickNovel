package com.lagradost.quicknovel

import android.view.View
import android.graphics.Rect
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.ui.ScrollVisibilityItem
import com.lagradost.quicknovel.ui.TextAdapter
import kotlin.math.abs

/** Owns only automatic scrolling; narration, highlighting and manual reading remain independent. */
internal class TTSAutoCentering(
    private val reader: RecyclerView,
    private val layout: LinearLayoutManager,
    private val adapter: TextAdapter,
    private val items: () -> List<SpanDisplay>,
    private val viewport: () -> TTSCenteringViewport,
    private val enabled: () -> Boolean,
) {
    private var sentence: TTSHelper.TTSLine? = null
    private val sentenceStarts = TTSSentenceStartCursor()
    private var generation = 0
    private var pendingTarget = false
    private var scrolling = false
    private var scheduled: Runnable? = null
    private var edgeSpace = 0 to 0
    private var touchingReader = false

    init {
        reader.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.setEmpty()
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return
                if (position == 0) outRect.top = edgeSpace.first
                if (position == state.itemCount - 1) outRect.bottom = edgeSpace.second
            }
        })
    }

    fun onSentenceStarted(start: TTSSentenceStart?) {
        sentence = sentenceStarts.take(start) ?: return
        request()
    }

    fun onWindowFocusRestored() {
        if (pendingTarget) request()
    }

    fun onReaderTouchStarted() {
        touchingReader = true
        cancel()
    }

    fun onReaderTouchEnded() {
        touchingReader = false
    }

    fun onContentChanged() {
        // A chapter insertion moves the first/last rows; remove old edge offsets too.
        if (edgeSpace != (0 to 0)) reader.invalidateItemDecorations()
        // Finish a request that began with speech but was waiting for its chapter to load.
        // Ordinary chapter updates must never start another centering attempt.
        if (pendingTarget) request()
    }

    private fun updateEdgeSpace() {
        val space = if (enabled()) {
            val location = IntArray(2)
            reader.getLocationInWindow(location)
            viewport().edgeSpaceWithin(TTSVerticalBounds(
                location[1] + reader.paddingTop,
                location[1] + reader.height - reader.paddingBottom,
            ))
        } else 0 to 0
        if (edgeSpace == space) return
        edgeSpace = space
        reader.invalidateItemDecorations()
    }

    fun cancel(clearTouch: Boolean = false) {
        if (clearTouch) touchingReader = false
        generation++
        scheduled?.let(reader::removeCallbacks)
        scheduled = null
        pendingTarget = false
        // A native smooth seek can be queued before RecyclerView enters SETTLING.
        if (scrolling && reader.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) reader.stopScroll()
        scrolling = false
        if (!enabled()) updateEdgeSpace()
    }

    private fun request() {
        val line = sentence
        cancel()
        if (line == null || !enabled() || touchingReader) return
        updateEdgeSpace()
        pendingTarget = true
        if (!reader.hasWindowFocus()) return
        val requestGeneration = generation
        val task = object : Runnable {
            private var layoutAttempts = 0

            override fun run() {
                scheduled = null
                if (requestGeneration != generation || !enabled() || touchingReader) return
                if (reader.hasPendingAdapterUpdates() || reader.isLayoutRequested) {
                    if (layoutAttempts++ < 3) {
                        scheduled = this
                        reader.postOnAnimation(this)
                    }
                    return
                }
                val position = items().indexOfFirst {
                    it is TextSpan && it.index == line.index && it.start < line.endChar && it.end > line.startChar
                }
                if (position < 0) return
                val holder = reader.findViewHolderForAdapterPosition(position)
                if (holder != null) {
                    val delta = scrollDelta(line, position) ?: return
                    pendingTarget = false
                    if (abs(delta) <= 2) return
                    scrolling = true
                    reader.smoothScrollBy(0, delta, DecelerateInterpolator(), 250)
                } else {
                    // Let RecyclerView find and measure off-screen paragraphs without an initial jump.
                    val scroller = object : LinearSmoothScroller(reader.context) {
                        override fun onTargetFound(targetView: View, state: RecyclerView.State, action: RecyclerView.SmoothScroller.Action) {
                            if (requestGeneration != generation || !enabled()) return
                            val delta = scrollDelta(line, targetPosition) ?: return
                            pendingTarget = false
                            if (abs(delta) > 2) action.update(0, delta, 250, DecelerateInterpolator())
                        }
                    }
                    scroller.targetPosition = position
                    scrolling = true
                    layout.startSmoothScroll(scroller)
                }
            }
        }
        scheduled = task
        reader.postOnAnimation(task)
    }

    private fun scrollDelta(line: TTSHelper.TTSLine, position: Int): Int? {
        val lines = adapter.getLines(ScrollVisibilityItem(position, reader.findViewHolderForAdapterPosition(position)))
        return ttsCenteringTarget(lines, line)?.scrollBy(viewport())
    }
}
