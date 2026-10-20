package com.lagradost.quicknovel

import java.text.BreakIterator
import java.util.Locale

internal data class JoinedSentenceRange(val start: Int, val end: Int)

/** Both sides of a removed paragraph break belong to the affected sentence(s). */
internal fun joinedSentenceRanges(text: String, plan: ParagraphFormatPlan): List<JoinedSentenceRange> {
    if (text.isEmpty() || plan.joins.isEmpty()) return emptyList()
    // Sentence boundaries also cover text which the current TTS parser cannot speak (e.g. Chinese).
    val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
    val ranges = ArrayList<JoinedSentenceRange>()
    for (join in plan.joins) {
        val boundary = plan.displayCharacter(join.start)
        val left = (boundary - 1).coerceIn(0, text.lastIndex)
        val right = (boundary + join.replacement.length).coerceIn(0, text.lastIndex)
        var start = sentences.preceding(left + 1).takeUnless { it == BreakIterator.DONE } ?: 0
        var end = sentences.following(right).takeUnless { it == BreakIterator.DONE } ?: text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start) continue
        val previous = ranges.lastOrNull()
        if (previous != null && start <= previous.end) {
            ranges[ranges.lastIndex] = JoinedSentenceRange(previous.start, maxOf(previous.end, end))
        } else ranges += JoinedSentenceRange(start, end)
    }
    return ranges
}

/** Clip to a reader row and leave rule-match backgrounds uncovered, regardless of span draw order. */
internal fun joinedHighlightsForParagraph(
    sentences: List<JoinedSentenceRange>,
    start: Int,
    end: Int,
    ruleMatches: List<TTSOverrideMatch>,
): List<JoinedSentenceRange> {
    if (end <= start) return emptyList()
    val exclusions = ruleMatches.filter { it.end > it.start }.sortedBy { it.start }
    return buildList {
        for (sentence in sentences) {
            var cursor = maxOf(sentence.start, start) - start
            val limit = minOf(sentence.end, end) - start
            if (cursor >= limit) continue
            for (match in exclusions) {
                if (match.end <= cursor) continue
                if (match.start >= limit) break
                if (match.start > cursor) add(JoinedSentenceRange(cursor, match.start))
                cursor = maxOf(cursor, match.end)
                if (cursor >= limit) break
            }
            if (cursor < limit) add(JoinedSentenceRange(cursor, limit))
        }
    }
}
