package com.lagradost.quicknovel

import org.junit.Test
import org.junit.Assert.*

class TTSHelperTest {

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
}

