package com.lagradost.quicknovel

/** Match the same speech sentences as playback, then map them into individual reader rows. */
internal class TTSReaderOverrideHighlights {
    private var engine = TTSOverrideEngine(emptyList())
    private val cache = linkedMapOf<TTSHelper.TTSLine, List<TTSOverrideMatch>>()
    val enabled: Boolean get() = engine.hasRules

    fun updateRules(rules: List<TTSWordOverride>) {
        engine = TTSOverrideEngine(rules)
        cache.clear()
    }

    fun forParagraph(
        sentences: List<TTSHelper.TTSLine>,
        chapter: Int,
        start: Int,
        end: Int,
    ): List<TTSOverrideMatch> {
        if (!enabled || end <= start) return emptyList()
        return sentences.asSequence().filter {
            it.index == chapter && it.startChar < end && it.endChar > start
        }.flatMap { line ->
            val matches = cache[line] ?: engine.preview(line.speakOutMsg).matches.also {
                // Retain only recent sentence geometry, not every chapter the user visits.
                if (cache.size >= 128) cache.remove(cache.keys.first())
                cache[line] = it
            }
            matches.asSequence().mapNotNull { match ->
                val left = maxOf(start, line.startChar + match.start)
                val right = minOf(end, line.endChar, line.startChar + match.end)
                if (right > left) TTSOverrideMatch(left - start, right - start) else null
            }
        }.toList()
    }
}
