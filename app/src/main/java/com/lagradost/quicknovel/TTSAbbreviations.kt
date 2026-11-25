package com.lagradost.quicknovel

import java.util.Locale

/**
 * Hybrid period handling: structural patterns need no word list, while ambiguous short forms use known rules.
 * Prepare once per document, then decide whether punctuation continues within the current paragraph.
 * A recognized abbreviation may still end a sentence; this helper does not expand spoken text.
 */
internal class TTSAbbreviations(private val text: String) {
    companion object {
        const val TERMINALS = ".!?。！？؟…"
        const val CLOSERS = "\"'”’»›」』)]}）】》"
        private val titles =
            setOf(
                "mr",
                "mrs",
                "ms",
                "ma'am",
                "dr",
                "prof",
                "capt",
                "cpt",
                "lt",
                "gen",
                "rev",
                "sgt",
                "st",
                "col",
                "maj",
                "cmdr",
                "adm",
                "fr",
                "hon",
                "gov",
                "sen",
                "rep",
                "pres",
            )
        private val placePrefixes = setOf("mt", "mts")
        private val continuations = setOf("e.g", "i.e", "vs", "cf", "esp")
        private val suffixes = setOf("jr", "sr", "etc")
        private val numbered = setOf("no", "ch", "chap", "vol", "fig", "pp", "p", "sec", "pt")
        private val months = setOf("jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec")
        private val sentenceStarters =
            setOf(
                "he",
                "she",
                "it",
                "they",
                "we",
                "you",
                "i",
                "this",
                "that",
                "these",
                "those",
                "there",
                "here",
                "then",
                "the",
                "a",
                "an",
                "meanwhile",
                "afterward",
                "however",
                "nevertheless",
                "suddenly",
                "finally",
                "today",
                "yesterday",
                "tomorrow",
                "next",
            )
        private val extensions =
            setOf("com", "org", "net", "edu", "gov", "io", "co", "uk", "txt", "epub", "pdf", "json", "html")
        private val dotted = Regex("(?<![\\p{L}\\p{N}])(?:\\p{L}\\.){2,}")
        private val addresses =
            Regex(
                "(?i)(?:https?://|www\\.)[^\\s<>\"“”]+|(?<![\\p{L}\\p{N}._%+\\-])[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}"
            )
    }

    private val protected = BooleanArray(text.length)
    private val acronyms = HashMap<Int, String>()

    init {
        for (match in addresses.findAll(text)) {
            var end = match.range.last + 1
            while (
                end > match.range.first &&
                    (text[end - 1] in TERMINALS || text[end - 1] in CLOSERS || text[end - 1] in ",;:")
            ) end--
            for (index in match.range.first until end) protected[index] = true
        }
        for (match in dotted.findAll(text)) {
            for (index in match.range.first until match.range.last) if (text[index] == '.') protected[index] = true
            acronyms[match.range.last] = match.value.dropLast(1).lowercase(Locale.ROOT)
        }
    }

    /** Dots inside initialisms, or punctuation inside URLs/emails, cannot end the sentence. */
    fun protectsPunctuation(position: Int): Boolean = protected[position]

    /** [limit] is the paragraph end, so an abbreviation never borrows context from a later paragraph. */
    fun continuesPeriod(period: Int, limit: Int): Boolean {
        if (protected[period]) return true
        val acronym = acronyms[period]
        val previous = text.getOrNull(period - 1)
        val following = text.getOrNull(period + 1)
        if (following?.isDigit() == true && (previous?.isLetterOrDigit() != true || previous.isDigit())) return true
        var wordStart = period
        while (wordStart > 0) {
            val character = text[wordStart - 1]
            // Keep apostrophes inside a title, without consuming surrounding quotation marks.
            val internalApostrophe =
                character in "'’" && wordStart > 1 &&
                    text[wordStart - 2].isLetter() && text[wordStart].isLetter()
            if (!character.isLetter() && !internalApostrophe) break
            wordStart--
        }
        val word = acronym ?: text.substring(wordStart, period).replace('’', '\'').lowercase(Locale.ROOT)
        var afterClosers = period + 1
        while (afterClosers < limit && text[afterClosers] in CLOSERS) afterClosers++
        val next = skipWhitespace(text, afterClosers, limit)
        if (next >= limit) return false
        var wordEnd = next
        while (wordEnd < limit && text[wordEnd].isLetterOrDigit()) wordEnd++
        val nextWord = text.substring(next, wordEnd)
        val nextInitial = nextWord.length == 1 && text.getOrNull(wordEnd) == '.'
        val startsSentence =
            nextWord.firstOrNull()?.isUpperCase() == true &&
                nextWord.lowercase(Locale.ROOT) in sentenceStarters &&
                !nextInitial
        if (word in continuations) return true
        if ((word in numbered || word in months) && text[next].isDigit()) return true
        if (word in titles || word in placePrefixes) {
            // A name can also look like a sentence starter ("Mr. He"). Ma'am can stand
            // alone as an address, and geographic abbreviations can end a sentence.
            // Other titles can stand alone after an article ("a Dr.").
            var previousEnd = wordStart
            while (
                previousEnd > 0 && text[previousEnd - 1].isWhitespace() && text[previousEnd - 1] !in "\r\n"
            ) previousEnd--
            var previousStart = previousEnd
            while (previousStart > 0 && text[previousStart - 1].isLetter()) previousStart--
            val standalone = word == "ma'am" || word in placePrefixes ||
                text.substring(previousStart, previousEnd).lowercase(Locale.ROOT) in setOf("a", "an")
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
}
