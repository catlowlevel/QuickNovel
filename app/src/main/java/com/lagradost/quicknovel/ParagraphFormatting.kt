package com.lagradost.quicknovel

/** Reversible presentation edits. Source coordinates always refer to the untouched rendered chapter. */
data class ParagraphJoin(val start: Int, val end: Int, val replacement: String)

class ParagraphFormatPlan(val sourceLength: Int, val joins: List<ParagraphJoin>) {
    fun apply(text: String): String =
        StringBuilder(text)
            .apply { for (join in joins.asReversed()) replace(join.start, join.end, join.replacement) }
            .toString()

    fun displayCharacter(sourceCharacter: Int): Int {
        val character = sourceCharacter.coerceIn(0, sourceLength)
        var removed = 0
        for (join in joins) {
            if (character < join.start) break
            if (character < join.end) return join.start - removed
            removed += join.end - join.start - join.replacement.length
        }
        return character - removed
    }

    fun sourceCharacter(displayCharacter: Int): Int {
        val length = sourceLength - joins.sumOf { it.end - it.start - it.replacement.length }
        val character = displayCharacter.coerceIn(0, length)
        var removed = 0
        for (join in joins) {
            val start = join.start - removed
            if (character < start) break
            if (character < start + join.replacement.length) return join.start
            removed += join.end - join.start - join.replacement.length
        }
        return character + removed
    }
}

/** Join likely sentence continuations, rather than collapsing every paragraph into one block. */
object ParagraphFormatting {
    private val breaks = Regex("[\\t \\u00a0]*[\\r\\n]+(?:[\\t \\u00a0]*[\\r\\n]+)*[\\t \\u00a0]*")
    private val listStart = Regex("^(?:[-–—*•▪◦]|\\d+[.)]|[A-Za-z][.)])(?:\\s|$)")
    private val headingStart = Regex("(?i)^(?:chapter|volume|book|part|episode)\\s+(?:\\d+|[ivxlcdm]+)(?:\\b|$)")
    private val abbreviation = Regex("(?i)(?:\\b(?:mr|mrs|ms|dr|prof|sr|jr|st|vs)|e\\.g|i\\.e)\\.$")
    private val continuation =
        setOf(
            "a",
            "an",
            "the",
            "of",
            "to",
            "in",
            "on",
            "at",
            "for",
            "from",
            "with",
            "and",
            "or",
            "but",
            "as",
            "by",
            "into",
            "than",
        )
    private val headingWords = setOf("a", "an", "the", "of", "to", "in", "on", "and", "or", "for")

    fun plan(text: String, protectedRanges: List<IntRange> = emptyList()): ParagraphFormatPlan {
        val joins = ArrayList<ParagraphJoin>()
        val boundaries = breaks.findAll(text).toList()
        for ((index, boundary) in boundaries.withIndex()) {
            val start = boundary.range.first
            val end = boundary.range.last + 1
            val leftStart = boundaries.getOrNull(index - 1)?.range?.last?.plus(1) ?: 0
            val rightEnd = boundaries.getOrNull(index + 1)?.range?.first ?: text.length
            // Structural spans protect their entire row, including both adjacent paragraph boundaries.
            if (protectedRanges.any { it.first < rightEnd && it.last >= leftStart }) continue
            val left = text.substring(leftStart, start).trim()
            val right = text.substring(end, rightEnd).trim()
            if (!canJoin(left, right)) continue
            val replacement =
                if (
                    (left.endsWith('-') && right.first().isLowerCase()) ||
                        (isUnspacedScript(left.last()) && isUnspacedScript(right.first()))
                )
                    ""
                else " "
            joins += ParagraphJoin(start, end, replacement)
        }
        return ParagraphFormatPlan(text.length, joins)
    }

    private fun isHeading(text: String): Boolean {
        if (headingStart.containsMatchIn(text)) return true
        val words = text.split(' ').filter { it.isNotEmpty() }
        return text.length < 80 &&
            words.size in 2..8 &&
            words.all { word -> word.all { it.isLetter() } && (word.first().isUpperCase() || word in headingWords) }
    }

    private fun canJoin(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        if ('\uFFFC' in left || '\uFFFC' in right || '\t' in left || '\t' in right) return false
        if (listStart.containsMatchIn(left) || listStart.containsMatchIn(right)) return false
        if (left.startsWith('#') || right.startsWith('#') || left.startsWith('>') || right.startsWith('>')) return false
        if (isHeading(left) || isHeading(right)) return false
        if (!left.any { it.isLetterOrDigit() } || !right.first().isLetterOrDigit()) return false
        val abbreviated = abbreviation.containsMatchIn(left)
        if (!abbreviated && left.last() in ".!?;:…。！？；：\"”’'」』»)]}") return false
        if (left.last() in "–—…") return false
        if (left.endsWith('-') && left.getOrNull(left.lastIndex - 1)?.isLetter() != true) return false
        val lastWord = left.takeLastWhile { it.isLetter() }.lowercase()
        val first = right.first()
        return first.isLowerCase() ||
            isUnspacedScript(first) ||
            first.code in 0xAC00..0xD7AF ||
            abbreviated ||
            left.endsWith(',') ||
            lastWord in continuation
    }

    private fun isUnspacedScript(character: Char) = character.code in 0x3400..0x9FFF || character.code in 0x3040..0x30FF
}
