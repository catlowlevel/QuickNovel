package com.lagradost.quicknovel

internal interface TTSPreviewOutput {
    val maxInputLength: Int
    fun prepare(ready: (Boolean) -> Unit)
    fun speak(text: String, finished: (Boolean) -> Unit)
    fun close()
}

/** Owns one preview at a time; late engine callbacks cannot restart stopped or dismissed previews. */
internal class TTSPreviewPlayback(
    private val createOutput: () -> TTSPreviewOutput,
    private val beforeRead: () -> Unit,
    private val changed: (Boolean) -> Unit,
    private val failed: () -> Unit,
) {
    var isReading = false
        private set
    private var generation = 0
    private var output: TTSPreviewOutput? = null
    private var closed = false

    fun read(text: String) {
        if (closed || text.isBlank()) return
        stop()
        beforeRead()
        val request = ++generation
        isReading = true
        changed(true)
        val speech = createOutput()
        output = speech
        speech.prepare { ready ->
            if (request != generation || closed) return@prepare
            if (!ready) {
                stop()
                failed()
                return@prepare
            }
            val chunks = splitTTSPreviewText(text, speech.maxInputLength)
            var index = 0
            fun next() {
                if (request != generation || closed) return
                if (index == chunks.size) {
                    stop()
                    return
                }
                speech.speak(chunks[index++]) { success ->
                    if (request != generation || closed) return@speak
                    if (success) next() else {
                        stop()
                        failed()
                    }
                }
            }
            next()
        }
    }

    fun stop() {
        generation++
        output?.close()
        output = null
        isReading = false
        changed(false)
    }

    fun close() {
        closed = true
        stop()
    }
}

/** Bound engine input without splitting a Unicode surrogate pair; prefer a nearby word boundary. */
internal fun splitTTSPreviewText(text: String, limit: Int): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val boundary = (end - 1 downTo start + (end - start) / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
        }
        val chunk = text.substring(start, end)
        if (chunk.isNotBlank()) chunks += chunk
        start = end
    }
    return chunks
}
