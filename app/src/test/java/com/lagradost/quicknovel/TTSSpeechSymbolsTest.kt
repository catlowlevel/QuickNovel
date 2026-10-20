package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class TTSSpeechSymbolsTest {
    private fun assertPreserved(text: String) {
        val lines = TTSHelper.ttsParseText(text, 3)
        assertTrue("Fixture must produce speech", lines.isNotEmpty())
        for (line in lines) assertEquals(text.substring(line.startChar, line.endChar), line.speakOutMsg)
        assertEquals(text, lines.joinToString(" ") { it.speakOutMsg })
    }

    @Test
    fun negativeNumbersKeepTheirSigns() {
        assertPreserved("The temperature was -3.5 degrees. It fell to −10 overnight.")
    }

    @Test
    fun hyphenatedNamesRangesAndNarrativeDashesKeepTheirMeaning() {
        assertPreserved("Mr. Smith-Jones waited 3–5 minutes—then left.")
        assertPreserved("A well-known name arrived.")
    }

    @Test
    fun quotationMarksAndSpanishPunctuationRemainInSpeech() {
        assertPreserved("He said «wait» and 「go」. ¿Estás listo?")
        assertPreserved("“Ready?” she asked. He nodded.")
    }

    @Test
    fun comparisonsMultiplicationPowersAndApproximationStayIntact() {
        assertPreserved("If x < 3 and y > 2, use 2 * 3, x^2, and ~5 units.")
    }

    @Test
    fun underscoresAndLiteralMarkupLikeSymbolsInIdentifiersAreNotGuessedAway() {
        assertPreserved("Use hero_name, <item>, *args, and ^start~end.")
    }

    @Test
    fun unicodeJoinersInsideWordsArePreserved() {
        assertPreserved("می\u200cروم.")
        assertPreserved("کلمه\u200dها باقی ماند.")
    }

    @Test
    fun decorationOnlyParagraphsAreSkippedWhileAdjacentSpeechAndOffsetsStayIntact() {
        val text = "Before.\n***\n---\n___\n~~~\n^^^\n<><>\n* * *\nAfter."
        val lines = TTSHelper.ttsParseText(text, 3)
        assertEquals(listOf("Before.", "After."), lines.map { it.speakOutMsg })
        assertEquals(text.indexOf("After"), lines.last().startChar)
        assertEquals(text.length, lines.last().endChar)
    }

    @Test
    fun pronunciationRulesCanReplacePreservedSymbolsWithoutChangingSourceOffsets() {
        val text = "The value was -3.5, or ~4 units."
        val line = TTSHelper.ttsParseText(text, 3).single()
        val rules =
            listOf(
                TTSWordOverride(find = "-", replacement = "minus "),
                TTSWordOverride(find = "~", replacement = "about "),
            )
        val spoken = TTSOverrideEngine(rules).prepare(line)!!
        assertEquals("The value was minus 3.5, or about 4 units.", spoken.speakOutMsg)
        assertEquals(text, line.speakOutMsg)
        assertEquals(line.startChar, spoken.startChar)
        assertEquals(line.endChar, spoken.endChar)
        val highlights = TTSReaderOverrideHighlights().apply { updateRules(rules) }
        val minus = text.indexOf('-')
        val tilde = text.indexOf('~')
        assertEquals(
            listOf(TTSOverrideMatch(minus, minus + 1), TTSOverrideMatch(tilde, tilde + 1)),
            highlights.forParagraph(listOf(line), 3, 0, text.length),
        )
    }
}
