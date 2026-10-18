package com.lagradost.quicknovel

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderBrowserLinksTest {
    private fun chapter(url: String) = ChapterData("Chapter", url, null, null)

    @Test
    fun eachChapterActionUsesItsOwnLinkIncludingFailedOrPreloadedChapters() {
        val chapters = listOf(chapter("https://example.com/book/1"), chapter("https://example.com/book/2?lang=en#text"))
        assertEquals("https://example.com/book/1", chapterBrowserUrlOrNull(chapters, 0) { it })
        assertEquals("https://example.com/book/2?lang=en#text", chapterBrowserUrlOrNull(chapters, 1) { it })
    }

    @Test
    fun relativeChapterLinksUseTheBooksProviderResolution() {
        val chapters = listOf(chapter("/book/chapter-2"), chapter("//cdn.example.com/chapter-3"))
        val resolve: (String) -> String = { "https://example.com/".toHttpUrl().resolve(it)!!.toString() }
        assertEquals("https://example.com/book/chapter-2", chapterBrowserUrlOrNull(chapters, 0, resolve))
        assertEquals("https://cdn.example.com/chapter-3", chapterBrowserUrlOrNull(chapters, 1, resolve))
    }

    @Test
    fun endOfBookMissingAndBlankChaptersDoNotOfferAnUnrelatedWebPage() {
        val chapters = listOf(chapter("https://example.com/book/1"), chapter(" "))
        for (index in listOf(-1, Int.MIN_VALUE, 1, 2, Int.MAX_VALUE)) {
            assertNull(chapterBrowserUrlOrNull(chapters, index) { error("Must not resolve a missing chapter") })
        }
        assertNull(chapterBrowserUrlOrNull(emptyList(), 0) { error("Must not resolve an empty book") })
    }

    @Test
    fun invalidOrUnsafeResolvedLinksAreNotExposedAsBrowserActions() {
        val chapters = listOf(chapter("/chapter"))
        for (resolved in listOf("", "chapter.xhtml", "file:///private/chapter", "javascript:alert(1)",
            "https://user:secret@example.com/chapter")) {
            assertNull(chapterBrowserUrlOrNull(chapters, 0) { resolved })
        }
    }

    @Test
    fun providerResolutionFailuresDoNotCrashTheReaderOrErrorRows() {
        assertNull(chapterBrowserUrlOrNull(listOf(chapter("/chapter")), 0) { error("Provider unavailable") })
    }
}
