package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class TTSCenteringRequestTest {
    private fun start(sequence: Long, chapter: Int = 3) =
        TTSSentenceStart(sequence, TTSHelper.TTSLine("The bus arrived.", 20, 36, chapter))

    @Test
    fun hidingAnUnfinishedScrollKeepsTheConsumedSentenceAvailableOnReturn() {
        val request = TTSCenteringRequest()
        val speech = start(1)
        request.onVisibilityChanged(true)
        assertTrue(request.onSentenceStarted(speech))
        assertTrue(request.canRun)

        request.onVisibilityChanged(false)
        assertFalse(request.canRun)
        assertFalse(request.onSentenceStarted(speech))
        request.onVisibilityChanged(true)
        assertTrue(request.canRun)
        assertSame(speech.line, request.sentence)
    }

    @Test
    fun aCompletedSentenceDoesNotRecenterOnReturnOrRepeatedDelivery() {
        val request = TTSCenteringRequest()
        val speech = start(1)
        request.onVisibilityChanged(true)
        request.onSentenceStarted(speech)
        request.complete()
        repeat(3) {
            request.onVisibilityChanged(false)
            request.onVisibilityChanged(true)
            assertFalse(request.onSentenceStarted(speech))
            assertFalse(request.canRun)
        }
    }

    @Test
    fun aNewBackgroundSentenceReplacesAnUnfinishedForegroundTarget() {
        val request = TTSCenteringRequest()
        request.onVisibilityChanged(true)
        request.onSentenceStarted(start(1))
        request.onVisibilityChanged(false)
        val latest = start(4, chapter = 7)
        assertTrue(request.onSentenceStarted(latest))
        assertFalse(request.canRun)
        request.onVisibilityChanged(true)
        assertTrue(request.canRun)
        assertSame(latest.line, request.sentence)
    }

    @Test
    fun aVisibleReaderCanFollowSuccessiveSpeechStartsWithoutAnotherVisibilityTransition() {
        val request = TTSCenteringRequest()
        request.onVisibilityChanged(true)
        for (sequence in 1L..3L) {
            val speech = start(sequence)
            assertTrue(request.onSentenceStarted(speech))
            assertTrue(request.canRun)
            assertSame(speech.line, request.sentence)
            request.complete()
            assertFalse(request.canRun)
        }
    }

    @Test
    fun fingerDownCancelsCenteringAndReleaseWaitsForTheNextSpeechStart() {
        val request = TTSCenteringRequest()
        request.onVisibilityChanged(true)
        request.onSentenceStarted(start(1))
        request.onTouchStarted()
        assertFalse(request.canRun)
        val duringTouch = start(2)
        request.onSentenceStarted(duringTouch)
        request.onTouchEnded()
        assertFalse(request.canRun)
        assertFalse(request.onSentenceStarted(duringTouch))
        request.onSentenceStarted(start(3))
        assertTrue(request.canRun)
    }

    @Test
    fun hidingClearsALostTouchWithoutReplayingTheCanceledSentence() {
        val request = TTSCenteringRequest()
        request.onVisibilityChanged(true)
        request.onSentenceStarted(start(1))
        request.onTouchStarted()
        request.onVisibilityChanged(false)
        request.onVisibilityChanged(true)
        assertFalse(request.canRun)
        request.onSentenceStarted(start(2))
        assertTrue(request.canRun)
    }

    @Test
    fun cancelingForPauseOrDisabledCenteringDoesNotReplayTheOldTargetOnReturn() {
        val request = TTSCenteringRequest()
        val speech = start(1)
        request.onVisibilityChanged(true)
        request.onSentenceStarted(speech)
        request.cancel()
        request.onVisibilityChanged(false)
        request.onVisibilityChanged(true)
        assertFalse(request.onSentenceStarted(speech))
        assertFalse(request.canRun)
        // Replaying the same source sentence is a new utterance and may center again.
        request.onSentenceStarted(TTSSentenceStart(2, speech.line))
        assertTrue(request.canRun)
    }
}
