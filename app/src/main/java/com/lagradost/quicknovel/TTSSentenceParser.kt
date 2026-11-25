package com.lagradost.quicknovel

internal data class TTSSentenceRange(val start: Int, val end: Int)

/** Deterministic sentence boundaries in untouched UTF-16 source text, independent of the TTS engine. */
internal object TTSSentenceParser {
    private const val terminals = TTSAbbreviations.TERMINALS
    private const val closers = TTSAbbreviations.CLOSERS
    private val namedAttribution =
        Regex(
            "^(?:\\p{Lu}[\\p{L}’'\\-]*)(?:[ \\t]+\\p{Lu}[\\p{L}’'\\-]*){0,2}[ \\t]+" +
                "(?:[\\p{L}]+ly[ \\t]+){0,2}" +
                "(?:said|asked|replied|shouted|whispered|yelled|murmured|exclaimed|cried|answered|added|continued|muttered|called)\\b"
        )

    fun split(text: String): List<TTSSentenceRange> {
        val abbreviations = TTSAbbreviations(text)
        val out = ArrayList<TTSSentenceRange>()
        var paragraph = 0
        for (index in text.indices) if (text[index] == '\r' || text[index] == '\n') {
            splitParagraph(text, paragraph, index, abbreviations, out)
            paragraph = index + 1
        }
        splitParagraph(text, paragraph, text.length, abbreviations, out)
        return out
    }

    private fun splitParagraph(
        text: String,
        from: Int,
        limit: Int,
        abbreviations: TTSAbbreviations,
        out: MutableList<TTSSentenceRange>,
    ) {
        var start = skipWhitespace(text, from, limit)
        var cursor = start
        val brackets = ArrayList<Int>()
        while (cursor < limit) {
            val character = text[cursor]
            if (abbreviations.protectsPunctuation(cursor)) {
                cursor++
                continue
            }
            // Square brackets delimit dialogue, whose internal sentences must still split.
            if (character in "({（【《") brackets += cursor
            if (character in ")}）】》" && brackets.isNotEmpty()) brackets.removeAt(brackets.lastIndex)
            if (character !in terminals) {
                cursor++
                continue
            }

            val ellipsis = character == '…' || (character == '.' && text.getOrNull(cursor + 1) == '.')
            if (character == '.' && !ellipsis && abbreviations.continuesPeriod(cursor, limit)) {
                cursor++
                continue
            }
            var punctuationEnd = cursor + 1
            while (
                punctuationEnd < limit &&
                    text[punctuationEnd] in terminals &&
                    !abbreviations.protectsPunctuation(punctuationEnd)
            ) punctuationEnd++
            var end = punctuationEnd
            while (end < limit && text[end] in closers) end++
            val next = skipWhitespace(text, end, limit)
            val hasClosingQuote = (punctuationEnd until end).any { text[it] in "\"'”’»›」』]" }
            val insideParenthesis = brackets.any { it > start }
            val continuingEllipsis =
                ellipsis &&
                    (cursor until punctuationEnd).all { text[it] == '.' || text[it] == '…' } &&
                    next < limit &&
                    text[next].isLowerCase()
            val attribution =
                hasClosingQuote &&
                    next < limit &&
                    (text[next].isLowerCase() ||
                        namedAttribution.containsMatchIn(text.substring(next, minOf(limit, next + 160))))
            if (insideParenthesis || continuingEllipsis || attribution) {
                cursor = punctuationEnd
                continue
            }
            add(text, start, end, out)
            start = skipWhitespace(text, end, limit)
            cursor = start
            brackets.clear()
        }
        add(text, start, limit, out)
    }

    private fun skipWhitespace(text: String, from: Int, limit: Int): Int {
        var cursor = from
        while (cursor < limit && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun add(text: String, from: Int, to: Int, out: MutableList<TTSSentenceRange>) {
        var end = to
        while (end > from && text[end - 1].isWhitespace()) end--
        var cursor = from
        while (cursor < end) {
            val point = Character.codePointAt(text, cursor)
            if (Character.isLetterOrDigit(point)) {
                out += TTSSentenceRange(from, end)
                return
            }
            cursor += Character.charCount(point)
        }
    }
}
