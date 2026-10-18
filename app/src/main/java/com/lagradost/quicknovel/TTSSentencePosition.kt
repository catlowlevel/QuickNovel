package com.lagradost.quicknovel

import kotlin.math.abs

internal data class TTSSentencePosition(val chapter: Int, val sentence: Int) {
    /** Move through speakable sentences, loading chapter lengths lazily and clamping at book ends.
     * A null length represents a failed or cancelled load, not an empty chapter.
     */
    suspend fun movedBy(
        offset: Int,
        chapterCount: Int,
        sentenceCount: suspend (Int) -> Int?,
    ): TTSSentencePosition? {
        if (offset == 0) return this
        val direction = if (offset > 0) 1 else -1
        var remaining = abs(offset.toLong())
        var position = this
        var count = sentenceCount(chapter) ?: return null

        while (remaining > 0) {
            val available = if (direction > 0) count - 1 - position.sentence else position.sentence
            if (remaining <= available) {
                return position.copy(sentence = position.sentence + direction * remaining.toInt())
            }
            remaining -= available
            position = position.copy(sentence = if (direction > 0) count - 1 else 0)

            var nextChapter = position.chapter + direction
            while (true) {
                if (nextChapter !in 0 until chapterCount) return position
                count = sentenceCount(nextChapter) ?: return null
                if (count > 0) break
                nextChapter += direction
            }
            position = TTSSentencePosition(nextChapter, if (direction > 0) 0 else count - 1)
            remaining--
        }
        return position
    }
}
