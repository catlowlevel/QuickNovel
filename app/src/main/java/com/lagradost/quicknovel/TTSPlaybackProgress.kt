package com.lagradost.quicknovel

/** A snapshot of narration, independent of the reader's visible chapter. */
class TTSPlaybackProgress private constructor(
    val chapter: Int,
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val sentenceFinished: Boolean,
) {
    // Count completed sentences, rather than showing 100% as the last sentence starts.
    val progress: Int
        get() = ((sentenceIndex.toLong() + if (sentenceFinished) 1 else 0) * MAX / sentenceCount).toInt()

    companion object {
        const val MAX = 10_000

        fun from(
            lines: List<TTSHelper.TTSLine>,
            sentenceIndex: Int,
            sentenceFinished: Boolean = false,
        ): TTSPlaybackProgress? {
            val line = lines.getOrNull(sentenceIndex) ?: return null
            return TTSPlaybackProgress(line.index, sentenceIndex, lines.size, sentenceFinished)
        }
    }
}
