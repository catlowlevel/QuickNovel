package com.lagradost.quicknovel

import org.junit.Test
import org.junit.Assert.*

class FormattingHelperTest {

    @Test
    fun testGetSemanticLastChar() {
        assertEquals('d', FormattingHelper.getSemanticLastChar("\"Hello Good\""))
        assertEquals(',', FormattingHelper.getSemanticLastChar("\"Hello,\""))
        assertEquals('.', FormattingHelper.getSemanticLastChar("\"Hello.\""))
        assertEquals('o', FormattingHelper.getSemanticLastChar("Hello"))
        assertNull(FormattingHelper.getSemanticLastChar(""))
        assertNull(FormattingHelper.getSemanticLastChar("\"\""))
        assertEquals('!', FormattingHelper.getSemanticLastChar("Hello!   \"\"\""))
    }

    @Test
    fun testGetSemanticFirstText() {
        assertEquals("Sir.\"", FormattingHelper.getSemanticFirstText("\"Sir.\""))
        assertEquals("sir.\"", FormattingHelper.getSemanticFirstText("\"sir.\""))
        assertEquals("Hello", FormattingHelper.getSemanticFirstText("Hello"))
        assertEquals("", FormattingHelper.getSemanticFirstText(""))
        assertEquals("", FormattingHelper.getSemanticFirstText("\"\""))
    }

    @Test
    fun testIsHeader() {
        assertTrue(FormattingHelper.isHeader("\"Primordial Garden, Tea Party Venue\""))
        assertTrue(FormattingHelper.isHeader("Chapter 1"))
        assertTrue(FormattingHelper.isHeader("Chapter One"))
        assertTrue(FormattingHelper.isHeader("THE PRIMORDIAL GARDEN"))
        assertTrue(FormattingHelper.isHeader("Introduction"))
        assertFalse(FormattingHelper.isHeader("\"The simple, wooden tavern appeared...\""))
        assertFalse(FormattingHelper.isHeader("He was the"))
        assertFalse(FormattingHelper.isHeader("This is a"))
        assertFalse(FormattingHelper.isHeader("\"Yes\""))
        assertFalse(FormattingHelper.isHeader("\"Yes,\""))
    }

    @Test
    fun testMergeLogic() {
        val sentenceEndChars = FormattingHelper.sentenceEndChars
        val quoteChars = FormattingHelper.quoteChars

        fun tryMerge(currentText: String, nextText: String): String? {
            val semanticLastChar = FormattingHelper.getSemanticLastChar(currentText)
            val isBroken = semanticLastChar != null && semanticLastChar !in sentenceEndChars && 
                (semanticLastChar in setOf(',', ';', ':') || !FormattingHelper.isHeader(currentText))
            if (!isBroken) return null

            val currentTrimmed = currentText.trim()
            val nextTrimmed = nextText.trim()

            val currentEndsWithQuote = currentTrimmed.isNotEmpty() && currentTrimmed.last() in quoteChars
            val nextStartsWithQuote = nextTrimmed.isNotEmpty() && nextTrimmed.first() in quoteChars

            val nextSemanticFirst = FormattingHelper.getSemanticFirstText(nextTrimmed)
            val firstLetter = nextSemanticFirst.firstOrNull { it.isLetter() }

            val shouldMerge = firstLetter != null

            if (!shouldMerge) return null

            var currentQuoteIdx = -1
            if (currentEndsWithQuote && nextStartsWithQuote) {
                for (idx in currentText.length - 1 downTo 0) {
                    val c = currentText[idx]
                    if (c.isWhitespace()) continue
                    if (c in quoteChars) {
                        currentQuoteIdx = idx
                        break
                    }
                }
            }

            var nextQuoteIdx = -1
            if (currentEndsWithQuote && nextStartsWithQuote) {
                for (idx in 0 until nextText.length) {
                    val c = nextText[idx]
                    if (c.isWhitespace()) continue
                    if (c in quoteChars) {
                        nextQuoteIdx = idx
                        break
                    }
                }
            }

            val currentMergedText = if (currentQuoteIdx != -1) {
                currentText.substring(0, currentQuoteIdx)
            } else {
                currentText
            }

            val nextMergedText = if (nextQuoteIdx != -1) {
                nextText.substring(nextQuoteIdx + 1)
            } else {
                nextText
            }

            val space = if (currentMergedText.isNotEmpty() && !currentMergedText.last().isWhitespace() &&
                nextMergedText.isNotEmpty() && !nextMergedText.first().isWhitespace()) " " else ""

            return currentMergedText + space + nextMergedText
        }

        // Test quote-boundary merging with capitalization (e.g. proper nouns/titles)
        assertEquals("\"We met with Mr. Smith.\"", tryMerge("\"We met with\"", "\"Mr. Smith.\""))
        assertEquals("\"He was the Chosen One.\"", tryMerge("\"He was the\"", "\"Chosen One.\""))

        // Test comma-boundary merging inside quotes with capitalization
        assertEquals("\"Yes, Captain.\"", tryMerge("\"Yes,\"", "\"Captain.\""))

        // Test normal merging (continuation starts with lowercase)
        assertEquals("This is a simple test.", tryMerge("This is a", "simple test."))
        assertEquals("Indeed, we should go.", tryMerge("Indeed,", "we should go."))
        
        // Test no merge (already complete sentence)
        assertNull(tryMerge("The book is on the table.", "It is very interesting."))
        assertNull(tryMerge("\"No way.\"", "\"That cannot be true.\""))

        // Test that any broken paragraph merges if the next starts with a letter
        assertEquals("The sun was Yesterday it rained.", tryMerge("The sun was", "Yesterday it rained."))

        // Test that headers (Title Case strings) do not merge
        assertNull(tryMerge("\"Chapter One: The Journey Begins\"", "\"The sun was rising over the mountains as they set off.\""))
    }
}
