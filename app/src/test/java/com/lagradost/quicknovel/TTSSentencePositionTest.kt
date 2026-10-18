package com.lagradost.quicknovel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TTSSentencePositionTest {
    private fun move(position: TTSSentencePosition, offset: Int, vararg lengths: Int) = runBlocking {
        position.movedBy(offset, lengths.size) { lengths[it] }
    }

    @Test
    fun movesRelativeToTheSelectedSentenceInEitherDirection() {
        assertEquals(TTSSentencePosition(0, 3), move(TTSSentencePosition(0, 1), 2, 5))
        assertEquals(TTSSentencePosition(0, 1), move(TTSSentencePosition(0, 3), -2, 5))
    }

    @Test
    fun crossesChapterBoundariesWithoutSkippingASentence() {
        assertEquals(TTSSentencePosition(1, 0), move(TTSSentencePosition(0, 2), 1, 3, 4))
        assertEquals(TTSSentencePosition(0, 2), move(TTSSentencePosition(1, 0), -1, 3, 4))
    }

    @Test
    fun accumulatedInputCanCrossSeveralChapters() {
        assertEquals(TTSSentencePosition(2, 1), move(TTSSentencePosition(0, 1), 5, 3, 2, 4))
        assertEquals(TTSSentencePosition(0, 1), move(TTSSentencePosition(2, 1), -5, 3, 2, 4))
    }

    @Test
    fun emptyChaptersDoNotConsumeNavigationSteps() {
        assertEquals(TTSSentencePosition(3, 0), move(TTSSentencePosition(0, 1), 1, 2, 0, 0, 3))
        assertEquals(TTSSentencePosition(0, 1), move(TTSSentencePosition(3, 0), -1, 2, 0, 0, 3))
    }

    @Test
    fun clampsAtTheFirstAndLastSpeakableSentencesIncludingEmptyEndChapters() {
        assertEquals(TTSSentencePosition(1, 0), move(TTSSentencePosition(1, 1), Int.MIN_VALUE, 0, 3, 0))
        assertEquals(TTSSentencePosition(1, 2), move(TTSSentencePosition(1, 1), Int.MAX_VALUE, 0, 3, 0))
    }

    @Test
    fun aFailedChapterLoadIsNotSilentlyTreatedAsAnEmptyChapter() = runBlocking {
        val position = TTSSentencePosition(0, 1)
        assertNull(position.movedBy(1, 3) { if (it == 1) null else 2 })
    }

    @Test
    fun loadsOnlyChaptersNeededToFindTheTargetSentence() = runBlocking {
        val loaded = mutableListOf<Int>()
        val result = TTSSentencePosition(0, 1).movedBy(1, 100) {
            loaded.add(it)
            2
        }
        assertEquals(TTSSentencePosition(1, 0), result)
        assertEquals(listOf(0, 1), loaded)
    }

    @Test
    fun opposingInputsCancelWithoutLoadingAnyChapters() = runBlocking {
        val position = TTSSentencePosition(1, 2)
        assertEquals(position, position.movedBy(0, 3) { error("No load needed") })
    }
}
