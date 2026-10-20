package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSReaderOverrideHighlightsTest {
    @Test
    fun chapterSentenceOffsetsMapToTheCorrectPositionsInsideAReaderRow() {
        val highlights = TTSReaderOverrideHighlights()
        highlights.updateRules(listOf(TTSWordOverride(find = "Li", replacement = "Lee")))
        val line = TTSHelper.TTSLine("Li met Li.", 100, 110, 4)
        assertEquals(
            listOf(TTSOverrideMatch(10, 12), TTSOverrideMatch(17, 19)),
            highlights.forParagraph(listOf(line), 4, 90, 115),
        )
    }

    @Test
    fun identicalCharacterPositionsInOtherChaptersCannotLeakHighlights() {
        val highlights = TTSReaderOverrideHighlights()
        highlights.updateRules(listOf(TTSWordOverride(find = "Li", replacement = "Lee")))
        val first = TTSHelper.TTSLine("Li", 100, 102, 4)
        val other = TTSHelper.TTSLine("Wu", 100, 102, 5)
        assertEquals(listOf(TTSOverrideMatch(0, 2)), highlights.forParagraph(listOf(first, other), 4, 100, 102))
        assertTrue(highlights.forParagraph(listOf(first, other), 5, 100, 102).isEmpty())
        assertTrue(highlights.forParagraph(listOf(first, other), 4, 110, 120).isEmpty())
    }

    @Test
    fun matchesAreClippedToTheirRowWithoutChangingSentenceCoordinates() {
        val highlights = TTSReaderOverrideHighlights()
        highlights.updateRules(listOf(TTSWordOverride(find = "(Li) (Wei)", replacement = "\$2 \$1", isRegex = true)))
        val line = TTSHelper.TTSLine("Li Wei", 100, 106, 2)
        assertEquals(listOf(TTSOverrideMatch(0, 3)), highlights.forParagraph(listOf(line), 2, 100, 103))
        assertEquals(listOf(TTSOverrideMatch(0, 2)), highlights.forParagraph(listOf(line), 2, 104, 106))
        assertEquals(100, line.startChar)
        assertEquals(106, line.endChar)
    }

    @Test
    fun matchingUsesPlaybackSentenceBoundariesAndAnchorsInsteadOfTheWholeParagraph() {
        val highlights = TTSReaderOverrideHighlights()
        val lines = listOf(TTSHelper.TTSLine("Li.", 100, 103, 2), TTSHelper.TTSLine("Wei.", 104, 108, 2))
        highlights.updateRules(listOf(TTSWordOverride(find = "Li. Wei.", replacement = "combined")))
        assertTrue(highlights.forParagraph(lines, 2, 100, 108).isEmpty())
        highlights.updateRules(listOf(TTSWordOverride(find = "^Wei", replacement = "Way", isRegex = true)))
        assertEquals(listOf(TTSOverrideMatch(4, 7)), highlights.forParagraph(lines, 2, 100, 108))
    }

    @Test
    fun preservedPunctuationStillMapsMatchedWordsToTheirDisplayedCharacterPositions() {
        val highlights = TTSReaderOverrideHighlights()
        highlights.updateRules(listOf(TTSWordOverride(find = "Li", replacement = "Lee")))
        val text = "-Li spoke."
        val lines = TTSHelper.ttsParseText(text, 2)
        assertEquals(text, lines.single().speakOutMsg)
        assertEquals(listOf(TTSOverrideMatch(1, 3)), highlights.forParagraph(lines, 2, 0, text.length))
    }

    @Test
    fun disablingEditingAndDeletingRulesInvalidateCachedSentenceHighlights() {
        val highlights = TTSReaderOverrideHighlights()
        val rule = TTSWordOverride(find = "Li", replacement = "Lee")
        val line = TTSHelper.TTSLine("li Li", 100, 105, 4)
        fun matches() = highlights.forParagraph(listOf(line), 4, 100, 105)
        highlights.updateRules(listOf(rule))
        assertEquals(listOf(TTSOverrideMatch(0, 2), TTSOverrideMatch(3, 5)), matches())
        highlights.updateRules(listOf(rule.copy(enabled = false)))
        assertTrue(matches().isEmpty())
        highlights.updateRules(listOf(rule.copy(caseSensitive = true)))
        assertEquals(listOf(TTSOverrideMatch(3, 5)), matches())
        highlights.updateRules(emptyList())
        assertTrue(matches().isEmpty())
    }
}
