package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSOverridePreviewTest {
    @Test
    fun highlightsHonorRegexCaseSettingsAndUseOriginalUtf16TextPositions() {
        val input = "😀 li Li LI Lily"
        val rule = TTSWordOverride(find = "\\b(Li)\\b", replacement = "\$1!", isRegex = true, caseSensitive = true)
        val sensitive = TTSOverrideEngine(listOf(rule)).preview(input)
        assertEquals("😀 li Li! LI Lily", sensitive.text)
        assertEquals(listOf(TTSOverrideMatch(6, 8)), sensitive.matches)
        assertEquals(1, sensitive.matchCount)
        val insensitive = TTSOverrideEngine(listOf(rule.copy(caseSensitive = false))).preview(input)
        assertEquals("😀 li! Li! LI! Lily", insensitive.text)
        assertEquals(listOf(TTSOverrideMatch(3, 5), TTSOverrideMatch(6, 8), TTSOverrideMatch(9, 11)), insensitive.matches)
        assertEquals(3, insensitive.matchCount)
    }

    @Test
    fun disabledAndInvalidRulesCannotProduceHighlightsOrCounts() {
        val rules = listOf(
            TTSWordOverride(find = "Li", replacement = "Lee", enabled = false),
            TTSWordOverride(find = "(", isRegex = true),
            TTSWordOverride(find = "(Li)", replacement = "\$2", isRegex = true),
        )
        assertEquals(TTSOverridePreview("Li", emptyList(), 0), TTSOverrideEngine(rules).preview("Li"))
    }

    @Test
    fun captureExpansionHighlightsTheWholeSourceMatchAndMatchesActualSpeech() {
        val engine = TTSOverrideEngine(listOf(TTSWordOverride(find = "(Li) (Wei)", replacement = "\$2, \$1", isRegex = true)))
        val input = "Li Wei spoke."
        val preview = engine.preview(input)
        assertEquals("Wei, Li spoke.", preview.text)
        assertEquals(engine.replace(input), preview.text)
        assertEquals(listOf(TTSOverrideMatch(0, 6)), preview.matches)
        assertEquals(1, preview.matchCount)
    }

    @Test
    fun aLaterRuleWhoseContextWasRemovedDoesNotFalselyHighlightOriginalText() {
        val rules = listOf(
            TTSWordOverride(find = "abc", replacement = "x"),
            TTSWordOverride(find = "(?<=abc )d", replacement = "Y", isRegex = true),
        )
        val preview = TTSOverrideEngine(rules).preview("abc d")
        assertEquals("x d", preview.text)
        assertEquals(listOf(TTSOverrideMatch(0, 3)), preview.matches)
        assertEquals(1, preview.matchCount)
    }

    @Test
    fun laterMatchesOnReplacementTextTraceBackToTheOriginalWord() {
        val engine = TTSOverrideEngine(listOf(
            TTSWordOverride(find = "cat", replacement = "dog"),
            TTSWordOverride(find = "\\bdog\\b", replacement = "wolf", isRegex = true),
        ))
        val preview = engine.preview("cat dog")
        assertEquals("wolf wolf", preview.text)
        assertEquals(engine.replace("cat dog"), preview.text)
        assertEquals(listOf(TTSOverrideMatch(0, 3), TTSOverrideMatch(4, 7)), preview.matches)
        assertEquals(3, preview.matchCount)
    }

    @Test
    fun deletedTextAndMatchesAcrossADeletionRemainHighlightedInTheOriginalInput() {
        val engine = TTSOverrideEngine(listOf(
            TTSWordOverride(find = "X", replacement = ""),
            TTSWordOverride(find = "ab", replacement = "word"),
        ))
        val preview = engine.preview("aXb")
        assertEquals("word", preview.text)
        assertEquals(engine.replace("aXb"), preview.text)
        assertEquals(listOf(TTSOverrideMatch(0, 3)), preview.matches)
        assertEquals(2, preview.matchCount)
        val removed = TTSOverrideEngine(listOf(TTSWordOverride(find = "aXb"))).preview("aXb")
        assertEquals("", removed.text)
        assertEquals(listOf(TTSOverrideMatch(0, 3)), removed.matches)
    }

    @Test
    fun zeroWidthInsertionsAreCountedWithoutFalselyHighlightingAdjacentLetters() {
        val engine = TTSOverrideEngine(listOf(
            TTSWordOverride(find = "^", replacement = "cat ", isRegex = true),
            TTSWordOverride(find = "cat", replacement = "dog"),
        ))
        val preview = engine.preview("Hi")
        assertEquals("dog Hi", preview.text)
        assertEquals(engine.replace("Hi"), preview.text)
        assertEquals(2, preview.matchCount)
        assertTrue(preview.matches.isEmpty())
        assertEquals(TTSOverridePreview("", emptyList(), 0), TTSOverrideEngine(emptyList()).preview(""))
    }
}
