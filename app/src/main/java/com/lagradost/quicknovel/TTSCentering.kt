package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.TextVisualLine
import kotlin.math.roundToInt

internal data class TTSVerticalBounds(val top: Int, val bottom: Int) {
    val height: Int
        get() = (bottom - top).coerceAtLeast(0)
}

internal data class TTSCenteringViewport(
    val bounds: TTSVerticalBounds,
    val screenCenter: Int,
    val obstruction: TTSVerticalBounds? = null,
) {
    private fun clearRegions(): List<TTSVerticalBounds> {
        if (bounds.height == 0) return emptyList()
        val blocked = obstruction
        if (blocked == null || blocked.bottom <= bounds.top || blocked.top >= bounds.bottom) return listOf(bounds)
        return listOf(
                TTSVerticalBounds(bounds.top, blocked.top.coerceIn(bounds.top, bounds.bottom)),
                TTSVerticalBounds(blocked.bottom.coerceIn(bounds.top, bounds.bottom), bounds.bottom),
            )
            .filter { it.height > 0 }
    }

    /** Move only far enough to avoid an actual overlap at the desired position. */
    fun focusTop(height: Int, desiredTop: Double = screenCenter - height / 2.0): Double? {
        val regions = clearRegions()
        val positions =
            regions
                .filter { it.height >= height }
                .map { desiredTop.coerceIn(it.top.toDouble(), (it.bottom - height).toDouble()) }
        return positions.minByOrNull { kotlin.math.abs(it - desiredTop) }
            // An oversized sentence must keep its beginning visible.
            ?: regions.maxByOrNull { it.height }?.top?.toDouble()
    }

    /** Allow either clear region to be reached at the beginning and end of the book. */
    fun edgeSpaceWithin(reader: TTSVerticalBounds): Pair<Int, Int> {
        val centers = clearRegions().map { screenCenter.coerceIn(it.top, it.bottom) }
        if (centers.isEmpty()) return 0 to 0
        return (centers.max() - reader.top).coerceAtLeast(0) to (reader.bottom - centers.min()).coerceAtLeast(0)
    }
}

internal data class TTSCenteringTarget(val paragraph: TTSVerticalBounds, val sentence: TTSVerticalBounds) {
    fun scrollBy(viewport: TTSCenteringViewport): Int {
        // Center the paragraph independently of floating controls. Only the spoken
        // sentence needs to be clear; other lines may remain behind the panel.
        val focus = if (paragraph.height <= viewport.bounds.height) paragraph else sentence
        val targetTop = viewport.copy(obstruction = null).focusTop(focus.height) ?: return 0
        val centeredDelta = focus.top - targetTop
        val sentenceTop = viewport.focusTop(sentence.height, sentence.top - centeredDelta) ?: return 0
        return (sentence.top - sentenceTop).roundToInt()
    }
}

/** Select by chapter and overlapping character ranges, never by a visible row's position. */
internal fun ttsCenteringTarget(lines: List<TextVisualLine>, sentence: TTSHelper.TTSLine): TTSCenteringTarget? {
    val spoken =
        lines.filter {
            it.index == sentence.index && it.endChar > sentence.startChar && it.startChar < sentence.endChar
        }
    if (spoken.isEmpty()) return null
    // Wait for both ends to be laid out rather than centering a partial sentence.
    if (spoken.minOf { it.startChar } > sentence.startChar || spoken.maxOf { it.endChar } < sentence.endChar)
        return null
    val paragraphs = spoken.mapTo(hashSetOf()) { it.innerIndex }
    val paragraph = lines.filter { it.index == sentence.index && it.innerIndex in paragraphs }
    return TTSCenteringTarget(
        paragraph = TTSVerticalBounds(paragraph.minOf { it.top }, paragraph.maxOf { it.bottom }),
        sentence = TTSVerticalBounds(spoken.minOf { it.top }, spoken.maxOf { it.bottom }),
    )
}
