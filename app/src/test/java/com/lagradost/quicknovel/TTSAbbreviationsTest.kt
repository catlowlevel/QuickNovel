package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class TTSAbbreviationsTest {
    @Test
    fun unlistedInitialismsAreRecognizedByShapeIncludingLowercaseAndUnicodeLetters() {
        for (name in listOf("Q.X.", "Z.V.N.", "R.S.T.U.", "q.x.", "Δ.Σ.")) {
            val text = "The $name network stopped. We waited."
            val abbreviations = TTSAbbreviations(text)
            val start = text.indexOf(name)
            for (offset in name.indices.filter { name[it] == '.' }) {
                assertTrue("Keep $name together", abbreviations.continuesPeriod(start + offset, text.length))
            }
            assertFalse(abbreviations.continuesPeriod(text.indexOf(". We"), text.length))
        }
    }

    @Test
    fun aStructurallyRecognizedInitialismCanStillEndTheSentence() {
        val text = "The unit was Q.X. We checked it."
        val abbreviations = TTSAbbreviations(text)
        val first = text.indexOf("Q.") + 1
        assertTrue(abbreviations.protectsPunctuation(first))
        assertFalse(abbreviations.protectsPunctuation(first + 2))
        assertFalse(abbreviations.continuesPeriod(first + 2, text.length))
    }

    @Test
    fun spacedPersonalInitialsStayWithTheirNameButStandalonePronounsDoNot() {
        val text = "V. R. Cole arrived. She waved."
        val abbreviations = TTSAbbreviations(text)
        assertTrue(abbreviations.continuesPeriod(text.indexOf('.'), text.length))
        assertTrue(abbreviations.continuesPeriod(text.indexOf("R.") + 1, text.length))
        assertFalse(abbreviations.continuesPeriod(text.indexOf(". She"), text.length))

        val standalone = "The last person was I. He left."
        assertFalse(TTSAbbreviations(standalone).continuesPeriod(standalone.indexOf("I.") + 1, standalone.length))
    }

    @Test
    fun knownAmbiguousFormsUseContextAndUnknownShortWordsDoNotGetGuessedAway() {
        for (text in listOf("Ask Mr. 42 for help.", "Visit Mt. Pine before noon.", "Ask Ma’am. Lee for help.")) {
            assertTrue(TTSAbbreviations(text).continuesPeriod(text.indexOf('.'), text.length))
        }
        for (text in listOf("The note said Mt. It was faded.", "The guide sat. We waited.", "The label read Zrx. Taylor left.")) {
            assertFalse(TTSAbbreviations(text).continuesPeriod(text.indexOf('.'), text.length))
        }
    }

    @Test
    fun numericReferencesAndDecimalPeriodsDoNotProtectTheFollowingSentenceEnd() {
        val text = "See Fig. 28. It cost 7.25 coins."
        val abbreviations = TTSAbbreviations(text)
        assertTrue(abbreviations.continuesPeriod(text.indexOf("Fig.") + 3, text.length))
        assertTrue(abbreviations.continuesPeriod(text.indexOf("7.25") + 1, text.length))
        assertFalse(abbreviations.continuesPeriod(text.indexOf("28.") + 2, text.length))
        assertFalse(abbreviations.continuesPeriod(text.lastIndexOf('.'), text.length))
        val missingNumber = "The reference ended with Fig. We asked for the page."
        assertFalse(TTSAbbreviations(missingNumber).continuesPeriod(missingNumber.indexOf('.'), missingNumber.length))
    }

    @Test
    fun addressPunctuationIsProtectedButSentencePunctuationAndClosingQuotesAreReleased() {
        val text = "Open “https://example.test/a.b?topic=one.two.” Then email A.Cole@example.NET."
        val abbreviations = TTSAbbreviations(text)
        assertTrue(abbreviations.protectsPunctuation(text.indexOf('?')))
        assertTrue(abbreviations.continuesPeriod(text.indexOf("one.two") + 3, text.length))
        assertTrue(abbreviations.continuesPeriod(text.indexOf("A.Cole") + 1, text.length))
        val ending = text.indexOf(".”")
        assertFalse(abbreviations.protectsPunctuation(ending))
        assertFalse(abbreviations.continuesPeriod(ending, text.length))
        assertFalse(abbreviations.protectsPunctuation(ending + 1))
        assertFalse(abbreviations.continuesPeriod(text.lastIndexOf('.'), text.length))
    }

    @Test
    fun paragraphLimitsAndEndOfInputStopAbbreviationsFromBorrowingMoreContext() {
        for (title in listOf("Dr.", "Mt.", "Ma’am.", "Q.X.")) {
            val text = "The note said $title\nTaylor arrived."
            val abbreviations = TTSAbbreviations(text)
            val limit = text.indexOf('\n')
            assertFalse(abbreviations.continuesPeriod(limit - 1, limit))
            assertFalse(TTSAbbreviations(title).continuesPeriod(title.lastIndex, title.length))
        }
    }
}
