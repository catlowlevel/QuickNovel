package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TTSPlaybackProgressTest {
    private fun lines(chapter: Int, count: Int) =
        List(count) { TTSHelper.TTSLine("Sentence $it.", it * 20, it * 20 + 12, chapter) }

    @Test
    fun progressCountsCompletedSentencesAndOnlyFinishesAfterTheLastSentence() {
        val chapterLines = lines(chapter = 3, count = 5)
        assertEquals(0, TTSPlaybackProgress.from(chapterLines, 0)?.progress)
        assertEquals(2_000, TTSPlaybackProgress.from(chapterLines, 0, sentenceFinished = true)?.progress)
        assertEquals(8_000, TTSPlaybackProgress.from(chapterLines, 4)?.progress)
        assertEquals(
            TTSPlaybackProgress.MAX,
            TTSPlaybackProgress.from(chapterLines, 4, sentenceFinished = true)?.progress,
        )

        val singleSentence = lines(chapter = 4, count = 1)
        assertEquals(0, TTSPlaybackProgress.from(singleSentence, 0)?.progress)
        assertEquals(
            TTSPlaybackProgress.MAX,
            TTSPlaybackProgress.from(singleSentence, 0, sentenceFinished = true)?.progress,
        )
    }

    @Test
    fun resumedAndNavigatedSelectionsUseTheNarratedChapterAndResetAcrossChapters() {
        val chapter = lines(chapter = 7, count = 10)
        val resumed = TTSPlaybackProgress.from(chapter, 6)!!
        assertEquals(7, resumed.chapter)
        assertEquals(6, resumed.sentenceIndex)
        assertEquals(10, resumed.sentenceCount)
        assertEquals(6_000, resumed.progress)
        assertEquals(2_000, TTSPlaybackProgress.from(chapter, 2)?.progress)

        val next = TTSPlaybackProgress.from(lines(chapter = 8, count = 3), 0)!!
        assertEquals(8, next.chapter)
        assertEquals(3, next.sentenceCount)
        assertEquals(0, next.progress)
    }

    @Test
    fun emptyChaptersAndMissingSentencesHaveNoProgress() {
        assertNull(TTSPlaybackProgress.from(emptyList(), 0))
        assertNull(TTSPlaybackProgress.from(lines(1, 3), -1))
        assertNull(TTSPlaybackProgress.from(lines(1, 3), 3))
    }
}
