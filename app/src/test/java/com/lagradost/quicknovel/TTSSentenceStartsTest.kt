package com.lagradost.quicknovel

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TTSSentenceStartsTest {
    private fun line(index: Int, start: Int = 0) = TTSHelper.TTSLine("A quiet harbor.", start, start + 14, index)

    @Test
    fun returningCollectorGetsLatestBackgroundSentenceOnceAndDoesNotReplayIntermediateOnes() = runBlocking {
        val starts = TTSSentenceStarts()
        val cursor = TTSSentenceStartCursor()
        val centered = mutableListOf<TTSHelper.TTSLine>()
        fun collect() =
            launch(start = CoroutineStart.UNDISPATCHED) {
                starts.latest.filterNotNull().collect { cursor.take(it)?.let(centered::add) }
            }
        val first = line(3)
        val skippedWhileAway = line(3, 15)
        val current = line(4)
        val foreground = collect()
        starts.started(first)
        yield()
        assertEquals(listOf(first), centered)
        foreground.cancelAndJoin()

        // The reader has no resumed lifecycle collector while narration continues at Home.
        starts.started(skippedWhileAway)
        starts.started(current)
        val returned = collect()
        assertEquals(listOf(first, current), centered)
        returned.cancelAndJoin()

        // Another lifecycle transition during the same utterance must preserve manual scrolling.
        val sameSentenceReturn = collect()
        assertEquals(listOf(first, current), centered)
        sameSentenceReturn.cancelAndJoin()
    }

    @Test
    fun aNewSpeechStartForTheSameCachedSentenceStillTriggersCentering() {
        val starts = TTSSentenceStarts()
        val cursor = TTSSentenceStartCursor()
        val sentence = line(3)
        starts.started(sentence)
        val firstSequence = starts.latest.value!!.sequence
        assertSame(sentence, cursor.take(starts.latest.value))
        assertNull(cursor.take(starts.latest.value))

        // Previous/replay can narrate the exact same TTSLine object again.
        starts.started(sentence)
        assertTrue(starts.latest.value!!.sequence > firstSequence)
        assertSame(sentence, cursor.take(starts.latest.value))
    }

    @Test
    fun stoppingClearsStaleReplayAndRestartingCannotCollideWithTheOldSpeechStart() {
        val starts = TTSSentenceStarts()
        val cursor = TTSSentenceStartCursor()
        val sentence = line(3)
        starts.started(sentence)
        val beforeStop = starts.latest.value!!.sequence
        assertSame(sentence, cursor.take(starts.latest.value))
        starts.clear()
        assertNull(starts.latest.value)
        assertNull(cursor.take(starts.latest.value))

        starts.started(sentence)
        assertTrue(starts.latest.value!!.sequence > beforeStop)
        assertSame(sentence, cursor.take(starts.latest.value))
    }

    @Test
    fun recreatedReaderCanRestoreTheCurrentSentenceWithItsOwnCursor() {
        val starts = TTSSentenceStarts()
        val sentence = line(5, 120)
        starts.started(sentence)
        val previousReader = TTSSentenceStartCursor()
        assertSame(sentence, previousReader.take(starts.latest.value))
        assertNull(previousReader.take(starts.latest.value))

        val recreatedReader = TTSSentenceStartCursor()
        assertSame(sentence, recreatedReader.take(starts.latest.value))
        assertNull(recreatedReader.take(starts.latest.value))
    }
}
