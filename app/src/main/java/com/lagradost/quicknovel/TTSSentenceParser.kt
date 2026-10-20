package com.lagradost.quicknovel

import java.util.Locale

internal data class TTSSentenceRange(val start: Int, val end: Int)

/** Deterministic sentence boundaries in untouched UTF-16 source text, independent of the TTS engine. */
internal object TTSSentenceParser {
    private const val terminals = ".!?。！？؟…"
    private const val closers = "\"'”’»›」』)]}）】》"
    private val titles = setOf("mr", "mrs", "ms", "dr", "prof", "capt", "cpt", "lt", "gen", "rev", "sgt", "st",
        "col", "maj", "cmdr", "adm", "fr", "hon", "gov", "sen", "rep", "pres")
    private val continuations = setOf("e.g", "i.e", "vs", "cf", "esp")
    private val suffixes = setOf("jr", "sr", "etc")
    private val numbered = setOf("no", "ch", "chap", "vol", "fig", "pp", "p", "sec", "pt")
    private val months = setOf("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec")
    private val sentenceStarters = setOf("he", "she", "it", "they", "we", "you", "i", "this", "that", "these", "those",
        "there", "here", "then", "the", "a", "an", "meanwhile", "afterward", "however", "nevertheless", "suddenly",
        "finally", "today", "yesterday", "tomorrow", "next")
    private val extensions = setOf("com", "org", "net", "edu", "gov", "io", "co", "uk", "txt", "epub", "pdf", "json", "html")
    private val dotted = Regex("(?<![\\p{L}\\p{N}])(?:\\p{L}\\.){2,}")
    private val addresses = Regex("(?i)(?:https?://|www\\.)[^\\s<>\"“”]+|(?<![\\p{L}\\p{N}._%+\\-])[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}")
    private val namedAttribution = Regex(
        "^(?:\\p{Lu}[\\p{L}’'\\-]*)(?:[ \\t]+\\p{Lu}[\\p{L}’'\\-]*){0,2}[ \\t]+" +
            "(?:[\\p{L}]+ly[ \\t]+){0,2}" +
            "(?:said|asked|replied|shouted|whispered|yelled|murmured|exclaimed|cried|answered|added|continued|muttered|called)\\b"
    )

    fun split(text: String): List<TTSSentenceRange> {
        val protected = BooleanArray(text.length)
        val acronyms = HashMap<Int, String>()
        for (match in addresses.findAll(text)) {
            var end = match.range.last + 1
            while (end > match.range.first && (text[end - 1] in terminals || text[end - 1] in closers || text[end - 1] in ",;:")) end--
            for (index in match.range.first until end) protected[index] = true
        }
        for (match in dotted.findAll(text)) {
            for (index in match.range.first until match.range.last) if (text[index] == '.') protected[index] = true
            acronyms[match.range.last] = match.value.dropLast(1).lowercase(Locale.ROOT)
        }
        val out = ArrayList<TTSSentenceRange>()
        var paragraph = 0
        for (index in text.indices) if (text[index] == '\r' || text[index] == '\n') {
            splitParagraph(text, paragraph, index, protected, acronyms, out)
            paragraph = index + 1
        }
        splitParagraph(text, paragraph, text.length, protected, acronyms, out)
        return out
    }

    private fun splitParagraph(text: String, from: Int, limit: Int, protected: BooleanArray,
                               acronyms: Map<Int, String>, out: MutableList<TTSSentenceRange>) {
        var start = skipWhitespace(text, from, limit)
        var cursor = start
        val brackets = ArrayList<Int>()
        while (cursor < limit) {
            val character = text[cursor]
            if (protected[cursor]) { cursor++; continue }
            if (character in "([{（【《") brackets += cursor
            if (character in ")]}）】》" && brackets.isNotEmpty()) brackets.removeAt(brackets.lastIndex)
            if (character !in terminals) { cursor++; continue }

            val ellipsis = character == '…' || (character == '.' && text.getOrNull(cursor + 1) == '.')
            if (character == '.' && !ellipsis && protectsPeriod(text, cursor, limit, acronyms[cursor])) {
                cursor++
                continue
            }
            var punctuationEnd = cursor + 1
            while (punctuationEnd < limit && text[punctuationEnd] in terminals && !protected[punctuationEnd]) punctuationEnd++
            var end = punctuationEnd
            while (end < limit && text[end] in closers) end++
            val next = skipWhitespace(text, end, limit)
            val hasClosingQuote = (punctuationEnd until end).any { text[it] in "\"'”’»›」』" }
            val insideParenthesis = brackets.any { it > start }
            val continuingEllipsis = ellipsis && (cursor until punctuationEnd).all { text[it] == '.' || text[it] == '…' } &&
                next < limit && text[next].isLowerCase()
            val attribution = hasClosingQuote && next < limit && (text[next].isLowerCase() ||
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

    private fun protectsPeriod(text: String, period: Int, limit: Int, acronym: String?): Boolean {
        val previous = text.getOrNull(period - 1)
        val following = text.getOrNull(period + 1)
        if (following?.isDigit() == true && (previous?.isLetterOrDigit() != true || previous.isDigit())) return true
        var wordStart = period
        while (wordStart > 0 && text[wordStart - 1].isLetter()) wordStart--
        val word = acronym ?: text.substring(wordStart, period).lowercase(Locale.ROOT)
        var afterClosers = period + 1
        while (afterClosers < limit && text[afterClosers] in closers) afterClosers++
        val next = skipWhitespace(text, afterClosers, limit)
        if (next >= limit) return false
        var wordEnd = next
        while (wordEnd < limit && text[wordEnd].isLetter()) wordEnd++
        val nextWord = text.substring(next, wordEnd)
        val nextInitial = nextWord.length == 1 && text.getOrNull(wordEnd) == '.'
        val startsSentence = nextWord.firstOrNull()?.isUpperCase() == true &&
            nextWord.lowercase(Locale.ROOT) in sentenceStarters && !nextInitial
        if (word in continuations) return true
        if ((word in numbered || word in months) && text[next].isDigit()) return true
        if (word in titles) {
            // A name can also look like a sentence starter ("Mr. He"). Only treat a title
            // as a standalone noun here when introduced by an indefinite article ("a Dr.").
            var previousEnd = wordStart
            while (previousEnd > 0 && text[previousEnd - 1].isWhitespace() && text[previousEnd - 1] !in "\r\n") previousEnd--
            var previousStart = previousEnd
            while (previousStart > 0 && text[previousStart - 1].isLetter()) previousStart--
            val standalone = text.substring(previousStart, previousEnd).lowercase(Locale.ROOT) in setOf("a", "an")
            return nextWord.isNotEmpty() && !(standalone && startsSentence)
        }
        if (word in suffixes || acronym != null) {
            if (text[next] in ",;:—–-") return true
            return nextWord.isNotEmpty() && !startsSentence
        }
        if (period - wordStart == 1 && text[wordStart].isUpperCase() && nextWord.firstOrNull()?.isUpperCase() == true) {
            return !startsSentence
        }
        // Dots inside lower-case domains/file names are not sentence endings. A capitalized
        // next word ("left.She") can still begin a sentence even if a source omitted the space.
        if (previous?.isLetter() == true && following?.isLetter() == true) {
            return following.isLowerCase() || nextWord.lowercase(Locale.ROOT) in extensions
        }
        return false
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
