package com.lagradost.quicknovel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WordCountTest {
    @Test
    fun emptyOrNonWordContentHasZeroWords() {
        for (text in listOf("", " \t\n", "— … !!!", "🐉 👩🏽‍💻 \uFFFC")) {
            assertEquals(text, 0, countWords(text))
        }
    }

    @Test
    fun handlesParagraphBreaksAndNonBreakingSpacesWithoutInflatingTheCount() {
        assertEquals(4, countWords("First\tsecond\n\nthird\u00a0fourth\u2003"))
    }

    @Test
    fun punctuationSeparatesWordsEvenWithoutSpaces() {
        assertEquals(3, countWords("Hello,world!Next?"))
    }

    @Test
    fun recognizesLettersBeyondAsciiAndCombiningAccents() {
        assertEquals(3, countWords("naïve café cafe\u0301"))
        assertEquals(2, countWords("Привет мир"))
        assertEquals(2, countWords("مرحبا بالعالم"))
    }

    @Test
    fun recognizesSupplementaryUnicodeLettersWithoutCountingEmojiOrImageMarkers() {
        assertEquals(2, countWords("𐐀 🐉 𐐨 \uFFFC"))
    }

    @Test
    fun numbersAndContractionsAreCountedAsWords() {
        assertEquals(4, countWords("Don't stop at 42."))
    }
}
