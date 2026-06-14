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
    fun testMergeLogic() {
        val sentenceEndChars = FormattingHelper.sentenceEndChars
        val quoteChars = FormattingHelper.quoteChars

        fun tryMerge(currentText: String, nextText: String): String? {
            val semanticLastChar = FormattingHelper.getSemanticLastChar(currentText)
            val isBroken = semanticLastChar != null && semanticLastChar !in sentenceEndChars
            if (!isBroken) return null

            val currentTrimmed = currentText.trim()
            val nextTrimmed = nextText.trim()

            val currentEndsWithQuote = currentTrimmed.isNotEmpty() && currentTrimmed.last() in quoteChars
            val nextStartsWithQuote = nextTrimmed.isNotEmpty() && nextTrimmed.first() in quoteChars

            val nextSemanticFirst = FormattingHelper.getSemanticFirstText(nextTrimmed)
            val firstLetter = nextSemanticFirst.firstOrNull { it.isLetter() }
            val nextStartsLowercase = firstLetter != null && firstLetter.isLowerCase()

            val shouldMerge = nextStartsLowercase ||
                    semanticLastChar == ',' || semanticLastChar == ';' || semanticLastChar == ':' ||
                    (currentEndsWithQuote && nextStartsWithQuote)

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

        // Test the user's two exact cases
        assertEquals("\"Hello Good Sir.\"", tryMerge("\"Hello Good\"", "\"Sir.\""))
        assertEquals("\"Hello, Sir\"", tryMerge("\"Hello,\"", "\"Sir\""))

        // Test normal merging
        assertEquals("Hello world", tryMerge("Hello", "world"))
        assertEquals("Hello, world", tryMerge("Hello,", "world"))
        
        // Test no merge (already complete sentence)
        assertNull(tryMerge("Hello.", "world"))
        assertNull(tryMerge("\"Hello.\"", "\"Sir.\""))

        // Test capitalization no-merge if not quoted/comma
        assertNull(tryMerge("Hello", "World"))
    }
}
