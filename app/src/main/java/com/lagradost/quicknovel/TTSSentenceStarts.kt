package com.lagradost.quicknovel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TTSSentenceStart(val sequence: Long, val line: TTSHelper.TTSLine)

/** Retain the latest actual speech start while lifecycle collectors are detached. */
internal class TTSSentenceStarts {
    private var sequence = 0L
    private val current = MutableStateFlow<TTSSentenceStart?>(null)
    val latest = current.asStateFlow()

    @Synchronized
    fun started(line: TTSHelper.TTSLine) {
        current.value = TTSSentenceStart(++sequence, line)
    }

    @Synchronized
    fun clear() {
        current.value = null
    }
}

/** Reattaching to the same utterance must not undo a reader's manual scroll. */
internal class TTSSentenceStartCursor {
    private var lastSequence: Long? = null

    fun take(start: TTSSentenceStart?): TTSHelper.TTSLine? {
        if (start == null || start.sequence == lastSequence) return null
        lastSequence = start.sequence
        return start.line
    }
}
