package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class JoinedSentenceHighlightsTest {
    private fun highlights(source: String): List<String> {
        val plan = ParagraphFormatting.plan(source)
        val formatted = plan.apply(source)
        return joinedSentenceRanges(formatted, plan).map { formatted.substring(it.start, it.end) }
    }

    @Test
    fun highlightsOnlyTheJoinedSentenceInsideAParagraphWithOtherSentences() {
        assertEquals(listOf("He looked at her with surprise."), highlights(
            "Before this happened. He looked at\n\nher with surprise. Then left."
        ))
    }

    @Test
    fun severalJoinsWithinOneSentenceProduceOneFullSentenceHighlight() {
        assertEquals(listOf("He was walking through the forest and stopped at the gate."), highlights(
            "He was walking\nthrough the forest\n\nand stopped at\n\nthe gate.\n\nAnother paragraph."
        ))
    }

    @Test
    fun separateAffectedSentencesDoNotHighlightTheUnchangedSentenceBetweenThem() {
        assertEquals(listOf("He looked at her.", "He walked into the forest."), highlights(
            "Before. He looked at\n\nher. Ordinary middle. He walked into\n\nthe forest. After."
        ))
    }

    @Test
    fun abbreviationJoinsIncludeBothSidesEvenWhenSentenceDetectionSplitsAfterTheTitle() {
        assertEquals(listOf("He visited Dr. Smith."), highlights("He visited Dr.\n\nSmith. Then left."))
    }

    @Test
    fun zeroWidthJoinsAndUnicodeUseTheExactDisplayedSentenceOffsets() {
        assertEquals(listOf("It was a well-known name."), highlights("It was a well-\n\nknown name. Next."))
        assertEquals(listOf("他走进森林。"), highlights("他走进\n\n森林。另一句。"))
        assertEquals(listOf("He looked at her 😀."), highlights("He looked at\n\nher 😀. Next."))
    }

    @Test
    fun rowsClipTheHighlightAndOverlappingRuleMatchesKeepTheirOwnBackground() {
        val sentences = listOf(JoinedSentenceRange(100, 130))
        assertEquals(listOf(JoinedSentenceRange(0, 5), JoinedSentenceRange(12, 20)),
            joinedHighlightsForParagraph(sentences, 105, 125, listOf(
                TTSOverrideMatch(8, 12), TTSOverrideMatch(5, 10)
            )))
        assertEquals(listOf(JoinedSentenceRange(5, 10)), joinedHighlightsForParagraph(sentences, 95, 110, listOf(
            TTSOverrideMatch(10, 20)
        )))
        assertTrue(joinedHighlightsForParagraph(sentences, 100, 130, listOf(TTSOverrideMatch(0, 30))).isEmpty())
        assertTrue(joinedHighlightsForParagraph(sentences, 130, 150, emptyList()).isEmpty())
        assertTrue(joinedHighlightsForParagraph(sentences, 100, 100, emptyList()).isEmpty())
    }

    @Test
    fun realPronunciationMatchesRemainVisibleWithinTheJoinedSentence() {
        val source = "He looked at\n\nher with surprise.\n\nThen left."
        val plan = ParagraphFormatting.plan(source)
        val text = plan.apply(source)
        val rules = TTSReaderOverrideHighlights().apply {
            updateRules(listOf(TTSWordOverride(find = "her", replacement = "them")))
        }
        val end = text.indexOf('\n')
        val matches = rules.forParagraph(TTSHelper.ttsParseText(text, 3), 3, 0, end)
        assertEquals(listOf(TTSOverrideMatch(13, 16)), matches)
        val amber = joinedHighlightsForParagraph(joinedSentenceRanges(text, plan), 0, end, matches)
        assertEquals(listOf("He looked at ", " with surprise."), amber.map { text.substring(it.start, it.end) })
        assertEquals("her", text.substring(matches.single().start, matches.single().end))
    }

    @Test
    fun unmodifiedTextAndProtectedStructuresNeverReceiveJoinedHighlights() {
        for (source in listOf("", "One complete sentence.", "Chapter 4\n\nHe was there.", "He stopped.\n\nThen left.")) {
            assertTrue(highlights(source).isEmpty())
        }
    }
}
