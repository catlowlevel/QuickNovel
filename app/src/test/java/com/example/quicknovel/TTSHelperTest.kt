package com.lagradost.quicknovel

import org.junit.Test
import org.junit.Assert.*

class TTSHelperTest {
    @Test
    fun testApplyOverrides_censoredWordWithAsterisk() {
        val result = TTSHelper.applyOverrides(
            "That was sh*t.",
            listOf(TTSOverride("sh*t", "shit"))
        )

        assertEquals("That was shit.", result)
    }

    @Test
    fun testTtsParseText_preservesAsteriskForOverrideMatching() {
        val lines = TTSHelper.ttsParseText("That was sh*t.", 0)

        assertEquals(1, lines.size)
        assertEquals("That was sh*t.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testCombineOverrides_localOverridesGlobalWithSameOriginal() {
        val combined = TTSHelper.combineOverrides(
            listOf(TTSOverride("foo", "local")),
            listOf(TTSOverride("foo", "global"), TTSOverride("bar", "globalBar"))
        )

        assertEquals(2, combined.size)
        assertEquals("local", combined[0].replacement)
        assertEquals("bar", combined[1].original)
    }

    @Test
    fun testFindOverrideRanges_literalAsteriskMatch() {
        val ranges = TTSHelper.findOverrideRanges("That was sh*t.", TTSOverride("sh*t", "shit"))

        assertEquals(listOf(9..12), ranges)
    }

    @Test
    fun testTtsParseText_standardSentence() {
        val text = "Hello world! This is a test."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("Hello world!", lines[0].speakOutMsg.trim())
        assertEquals("This is a test.", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_mrAbbreviation() {
        val text = "Hello Mr. Foo"
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("Hello Mr. Foo", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_multipleAbbreviations() {
        val text = "Dr. House saw Mr. Foo at 5:00 p.m. yesterday."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("Dr. House saw Mr. Foo at 5:00 p.m. yesterday.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_singleLetterInitials() {
        val text = "J. K. Rowling wrote Harry Potter."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("J. K. Rowling wrote Harry Potter.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_decimalsAndColons() {
        val text = "The value of pi is 3.14, and the ratio is 1:2."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("The value of pi is 3.14, and the ratio is 1:2.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_sentenceBoundaryAfterAbbreviation() {
        val text = "We saw Dr. Smith. He was nice."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("We saw Dr. Smith.", lines[0].speakOutMsg.trim())
        assertEquals("He was nice.", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_multiPeriodAbbreviations() {
        val text = "This is e.g. a test of abbreviations."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("This is e.g. a test of abbreviations.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_abbreviationWithNumber() {
        val text = "Room No. 2 is currently empty."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("Room No. 2 is currently empty.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_numberInQuotes() {
        val text = "\"9999\""
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("\"9999\"", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_textInQuotes() {
        val text = "\"Hello\""
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("\"Hello\"", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_dialogueWithQuotes() {
        val text = "\"Hello!\" she said."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("\"Hello!\"", lines[0].speakOutMsg.trim())
        assertEquals("she said.", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_onlyInvalidChars() {
        val text = "\"\" \t \n [] ... «»"
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(0, lines.size)
    }

    @Test
    fun testTtsParseText_decimalAndCurrency() {
        val text = "It costs $99.99 today."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("It costs $99.99 today.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_multipleDialogues() {
        val text = "\"Hello!\" she said, \"how are you?\""
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("\"Hello!\"", lines[0].speakOutMsg.trim())
        assertEquals("she said, \"how are you?\"", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_bracketedText() {
        val text = "[Briefing] This is a test [with bracketed notes]."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("[Briefing] This is a test [with bracketed notes].", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_multipleParagraphs() {
        val text = "First paragraph.\n\nSecond paragraph."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("First paragraph.", lines[0].speakOutMsg.trim())
        assertEquals("Second paragraph.", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_ellipsisWithSpace() {
        val text = "Hello... World."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(2, lines.size)
        assertEquals("Hello...", lines[0].speakOutMsg.trim())
        assertEquals("World.", lines[1].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_ellipsisWithoutSpace() {
        val text = "Hello...World."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("Hello...World.", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_singleQuotes() {
        val text = "He said, 'No way!'"
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("He said, 'No way!'", lines[0].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_mixedPunctuation() {
        val text = "What?! Really? Yes!"
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(3, lines.size)
        assertEquals("What?!", lines[0].speakOutMsg.trim())
        assertEquals("Really?", lines[1].speakOutMsg.trim())
        assertEquals("Yes!", lines[2].speakOutMsg.trim())
    }

    @Test
    fun testTtsParseText_dashes() {
        val text = "This is a well-known fact — at least, to most."
        val lines = TTSHelper.ttsParseText(text, 0)
        
        assertEquals(1, lines.size)
        assertEquals("This is a well-known fact — at least, to most.", lines[0].speakOutMsg.trim())
    }
}
