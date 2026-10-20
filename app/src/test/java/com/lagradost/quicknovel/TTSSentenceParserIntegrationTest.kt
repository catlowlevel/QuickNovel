package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class TTSSentenceParserIntegrationTest {
    @Test
    fun speechPreservesSourceCoordinatesForRulesAfterEllipsesAndHyphens() {
        val text = "“Well-known...” said Dr. Smith. Then he left."
        val lines = TTSHelper.ttsParseText(text, 6)
        assertEquals(2, lines.size)
        for (line in lines) assertEquals(text.substring(line.startChar, line.endChar), line.speakOutMsg)
        val highlights = TTSReaderOverrideHighlights().apply {
            updateRules(listOf(TTSWordOverride(find = "Smith", replacement = "Smyth")))
        }
        val start = text.indexOf("Smith")
        assertEquals(listOf(TTSOverrideMatch(start, start + 5)), highlights.forParagraph(lines, 6, 0, text.length))
        assertEquals(text.substring(0, text.indexOf(" Then")), text.substring(lines[0].startChar, lines[0].endChar))
    }

    @Test
    fun pronunciationRulesCanHighlightEntirelyNonLatinSentences() {
        val text = "你好。再见！"
        val lines = TTSHelper.ttsParseText(text, 6)
        val highlights = TTSReaderOverrideHighlights().apply {
            updateRules(listOf(TTSWordOverride(find = "再见", replacement = "回头见")))
        }
        assertEquals(listOf(TTSOverrideMatch(3, 5)), highlights.forParagraph(lines, 6, 0, text.length))
    }

    @Test
    fun oldOrdinalBookmarksResolveTheSameTextAfterNewSentenceBoundaries() {
        val cases = listOf(
            Triple("He met Ms. Smith. He left.", 1, "Smith"),
            Triple("He said: wait here. Then left.", 1, "wait here"),
            Triple("Stop! Run. He left.", 1, "He left"),
        )
        for ((text, ordinal, word) in cases) {
            val character = legacyTtsSentenceCharacter(text, ordinal)
            assertEquals(text.indexOf(word), character)
            val current = TTSHelper.ttsParseText(text, 2)
            val migrated = TTSPlaybackBookmark(2, ordinal, "chapter", character)
            assertEquals(current.sentenceAtCharacter(text.indexOf(word)),
                migrated.resolveSentence(current, { it }, current))
        }
    }

    @Test
    fun oldBookmarksAlsoSurviveParagraphFormattingAndSkipInvalidOrdinals() {
        val source = "He met Ms. Smith at\n\nthe gate. Then left."
        val plan = ParagraphFormatting.plan(source)
        val text = plan.apply(source)
        val character = legacyTtsSentenceCharacter(source, 3)
        assertEquals(source.indexOf("Then"), character)
        val bookmark = TTSPlaybackBookmark(2, 3, "chapter", character)
        val lines = TTSHelper.ttsParseText(text, 2)
        assertEquals(1, bookmark.resolveSentence(lines, plan::displayCharacter, emptyList()))
        assertNull(legacyTtsSentenceCharacter(source, -1))
        assertNull(legacyTtsSentenceCharacter(source, 50))
        assertNull(legacyTtsSentenceCharacter("", 0))
    }

    @Test
    fun joinedHighlightsAndPlaybackUseTheSameAcronymAndSentenceBoundaries() {
        val source = "The A.I. watched\n\nhim. Another sentence."
        val plan = ParagraphFormatting.plan(source)
        val text = plan.apply(source)
        val highlights = joinedSentenceRanges(text, plan)
        val spoken = TTSHelper.ttsParseText(text, 2)
        assertEquals(listOf("The A.I. watched him."), highlights.map { text.substring(it.start, it.end) })
        assertEquals(spoken.first().startChar, highlights.single().start)
        assertEquals(spoken.first().endChar, highlights.single().end)
    }

    @Test(timeout = 3_000)
    fun longUnpunctuatedTextDoesNotMakeAddressDetectionRetryAtEveryCharacter() {
        val text = "a".repeat(100_000)
        val lines = TTSHelper.ttsParseText(text, 2)
        assertEquals(1, lines.size)
        assertEquals(text.length, lines.single().endChar)
        assertEquals(text, lines.single().speakOutMsg)
    }
}
