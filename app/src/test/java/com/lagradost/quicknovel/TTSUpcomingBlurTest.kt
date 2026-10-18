package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TTSUpcomingBlurTest {
    private val sentence = TTSHelper.TTSLine("Current.", 100, 110, 4)

    private fun range(
        current: TTSHelper.TTSLine? = sentence,
        chapter: Int = 4,
        start: Int = 90,
        end: Int = 140,
        enabled: Boolean = true,
        status: TTSHelper.TTSStatus = TTSHelper.TTSStatus.IsRunning,
    ) = upcomingTTSBlurRange(enabled, status, current, chapter, start, end)

    @Test
    fun aSharedParagraphKeepsPastAndCurrentSentencesSharp() {
        assertEquals(TTSBlurRange(20, 50), range())
        assertNull(range(start = 0, end = 90))
        assertNull(range(start = 100, end = 110))
    }

    @Test
    fun futureChaptersBlurFullyAndEarlierChaptersRemainReadableRegardlessOfCharacterOffsets() {
        assertNull(range(chapter = 3, start = 1_000, end = 1_100))
        assertEquals(TTSBlurRange(0, 20), range(chapter = 5, start = 0, end = 20))
    }

    @Test
    fun exclusiveSentenceEndsNeverBlurTheLastSpokenCharacterOrLeaveTheNextOneSharp() {
        assertNull(range(start = 109, end = 110))
        assertEquals(TTSBlurRange(0, 1), range(start = 110, end = 111))
        assertEquals(TTSBlurRange(0, 20), range(start = 120, end = 140))
    }

    @Test
    fun sentencesCrossingParagraphBoundariesStayClearUntilTheirActualEnd() {
        val crossing = sentence.copy(startChar = 80, endChar = 130)
        assertNull(range(current = crossing, start = 70, end = 100))
        assertNull(range(current = crossing, start = 101, end = 120))
        assertEquals(TTSBlurRange(9, 29), range(current = crossing, start = 121, end = 150))
    }

    @Test
    fun stoppingDisablingOrLosingTheSentenceMakesAllTextReadable() {
        assertNull(range(status = TTSHelper.TTSStatus.IsStopped))
        assertNull(range(enabled = false))
        assertNull(range(enabled = false, status = TTSHelper.TTSStatus.IsPaused))
        assertNull(range(current = null))
        assertEquals(TTSBlurRange(20, 50), range())
    }

    @Test
    fun pausingKeepsFutureTextBlurredWithoutBlurringTheCurrentOrPastSentences() {
        val paused = TTSHelper.TTSStatus.IsPaused
        assertEquals(range(), range(status = paused))
        assertNull(range(status = paused, start = 100, end = 110))
        assertNull(range(status = paused, chapter = 3))
        assertEquals(TTSBlurRange(0, 20), range(status = paused, chapter = 5, start = 0, end = 20))
    }

    @Test
    fun forwardAndBackwardNavigationRecomputeBlurFromTheSelectedSentence() {
        for (status in listOf(TTSHelper.TTSStatus.IsRunning, TTSHelper.TTSStatus.IsPaused)) {
            assertEquals(TTSBlurRange(20, 50), range(status = status))
            assertEquals(TTSBlurRange(40, 50), range(status = status, current = sentence.copy(startChar = 111, endChar = 130)))
            assertEquals(TTSBlurRange(10, 50), range(status = status, current = sentence.copy(startChar = 90, endChar = 100)))
            assertNull(range(status = status, current = sentence.copy(startChar = 131, endChar = 140)))
        }
    }

    @Test
    fun pronunciationReplacementsCannotMoveTheBlurBoundaryInTheDisplayedBook() {
        val original = TTSHelper.TTSLine("Li.", 100, 103, 4)
        val spoken = TTSOverrideEngine(listOf(TTSWordOverride(find = "Li", replacement = "A much longer pronunciation")))
            .prepare(original)!!
        assertEquals(103, spoken.endChar)
        assertEquals(TTSBlurRange(13, 50), range(current = original))
        assertEquals(range(current = original), range(current = spoken))
    }

    @Test
    fun unicodeOffsetsPreserveTheWholeSpokenSentenceAndTheNextEmoji() {
        val text = "Read😀. Next😀."
        val spoken = "Read😀."
        val blur = range(current = sentence.copy(speakOutMsg = spoken, startChar = 0, endChar = spoken.length),
            start = 0, end = text.length)!!
        assertEquals(" Next😀.", text.substring(blur.start, blur.end))
        assertEquals(spoken, text.substring(0, blur.start))
    }

    @Test
    fun emptyOrInvalidRowsCannotProduceAnInvalidSpan() {
        assertNull(range(start = 120, end = 120))
        assertNull(range(start = 120, end = 110))
    }
}
