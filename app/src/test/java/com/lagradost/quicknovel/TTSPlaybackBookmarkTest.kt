package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TTSPlaybackBookmarkTest {
    @Test
    fun bookmarkSurvivesTheAppsJsonStorageRoundTrip() {
        val saved = TTSPlaybackBookmark(12, 37, "https://source.example/chapter/13")
        val json = DataStore.mapper.writeValueAsString(saved)
        val restored = DataStore.mapper.readValue(json, TTSPlaybackBookmark::class.java)
        assertEquals(saved, restored)
    }

    @Test
    fun resumesTheNarratedChapterAndSentenceAmongSeveralLoadedChapters() {
        val saved = TTSPlaybackBookmark(2, 7, "chapter-c")
        assertEquals(2, saved.resolveChapter(listOf("chapter-a", "chapter-b", "chapter-c")))
        assertEquals(7, saved.resolveSentence(20))
    }

    @Test
    fun findsTheSavedChapterAfterTheChapterListIsReordered() {
        val saved = TTSPlaybackBookmark(1, 7, "chapter-b")
        assertEquals(2, saved.resolveChapter(listOf("new-chapter", "chapter-a", "chapter-b")))
    }

    @Test
    fun duplicateChapterIdentitiesPreferTheOriginalIndexWhenItStillMatches() {
        val saved = TTSPlaybackBookmark(2, 7, "shared-page")
        assertEquals(2, saved.resolveChapter(listOf("shared-page", "other", "shared-page")))
    }

    @Test
    fun removedChaptersAndInvalidBookmarksFallBackInsteadOfResumingAnUnrelatedChapter() {
        val chapterIds = listOf("chapter-a", "chapter-b")
        for (saved in listOf(
            TTSPlaybackBookmark(1, 7, "removed"),
            TTSPlaybackBookmark(-1, 7, "chapter-a"),
            TTSPlaybackBookmark(0, -1, "chapter-a"),
            TTSPlaybackBookmark(0, 7, ""),
        )) {
            assertNull(saved.resolveChapter(chapterIds))
        }
    }

    @Test
    fun shorterOrEmptyChaptersCannotProduceAnOutOfBoundsSentence() {
        val saved = TTSPlaybackBookmark(1, 37, "chapter-b")
        assertEquals(3, saved.resolveSentence(4))
        assertNull(saved.resolveSentence(0))
    }

    @Test
    fun readFromHereUsesTheContainingSentenceInsteadOfSkippingIt() {
        val lines = listOf(
            TTSHelper.TTSLine("First sentence.", 0, 15, 2),
            TTSHelper.TTSLine("Second sentence.", 16, 32, 2),
            TTSHelper.TTSLine("Third sentence.", 33, 48, 2),
        )
        assertEquals(1, lines.sentenceAtCharacter(23))
        assertEquals(2, lines.sentenceAtCharacter(33))
        assertEquals(0, lines.sentenceAtCharacter(-1))
        assertEquals(2, lines.sentenceAtCharacter(100))
        assertNull(emptyList<TTSHelper.TTSLine>().sentenceAtCharacter(0))
    }

    @Test
    fun sameTitleBooksHaveIndependentKeysAcrossProvidersAuthorsAndEpubFiles() {
        val keys = listOf(
            ttsPlaybackBookKey("quick", "Provider A", "Author A", "Same Title"),
            ttsPlaybackBookKey("quick", "Provider B", "Author A", "Same Title"),
            ttsPlaybackBookKey("quick", "Provider A", "Author B", "Same Title"),
            ttsPlaybackBookKey("epub", "content://books/first.epub"),
            ttsPlaybackBookKey("epub", "content://books/second.epub"),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.first(), ttsPlaybackBookKey("quick", "Provider A", "Author A", "Same Title"))
        assertNotEquals(ttsPlaybackBookKey("a", "bc"), ttsPlaybackBookKey("ab", "c"))
    }
}
