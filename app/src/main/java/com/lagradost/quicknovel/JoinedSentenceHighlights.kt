package com.lagradost.quicknovel

internal data class JoinedSentenceRange(val start: Int, val end: Int)

/** Both sides of a removed paragraph break belong to the affected sentence(s). */
internal fun joinedSentenceRanges(text: String, plan: ParagraphFormatPlan): List<JoinedSentenceRange> {
    if (text.isEmpty() || plan.joins.isEmpty()) return emptyList()
    val sentences = TTSSentenceParser.split(text)
    if (sentences.isEmpty()) return emptyList()
    val ranges = ArrayList<JoinedSentenceRange>()
    var first = 0
    for (join in plan.joins) {
        val boundary = plan.displayCharacter(join.start)
        val left = (boundary - 1).coerceIn(0, text.lastIndex)
        val right = (boundary + join.replacement.length).coerceIn(0, text.lastIndex)
        while (first < sentences.lastIndex && sentences[first].end <= left) first++
        var last = first
        while (last < sentences.lastIndex && sentences[last + 1].start <= right) last++
        val start = sentences[first].start
        val end = sentences[last].end
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
