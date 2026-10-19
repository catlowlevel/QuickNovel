package com.lagradost.quicknovel.ai

import org.junit.Assert.*
import org.junit.Test

class AiChapterTextViewsTest {
    private val chapter = AiChapter("novel", "chapter-4", "Novel", "Chapter 4", "Full original chapter text.")
    private val output = AiSummaryOutput("A short recap.")

    @Test
    fun summaryCanReplaceThePresentationWhileTheOriginalTextAndRestorePositionRemainIntact() {
        val views = AiChapterTextViews<String>()
        val position = AiOriginalPosition(7, 940)
        views.show(chapter, output, "summary-rendering", position)
        assertEquals("summary-rendering", views.displayed(chapter)?.content)
        assertEquals(position, views.originalPosition(chapter.bookId, chapter.chapterId))
        assertEquals("Full original chapter text.", chapter.text)
        assertEquals(position, views.restore(chapter))
        assertNull(views.displayed(chapter))
        assertNull(views.originalPosition(chapter.bookId, chapter.chapterId))
        assertEquals(output, views.saved(chapter)?.output)
        assertNull(views.restore(chapter))
    }

    @Test
    fun reopeningTheSummaryAfterReadingMoreOriginalTextRestoresTheNewReadingPosition() {
        val views = AiChapterTextViews<String>()
        views.show(chapter, output, "summary", AiOriginalPosition(2, 100))
        views.restore(chapter)
        val saved = views.saved(chapter)!!
        views.show(chapter, saved.output, saved.content, AiOriginalPosition(9, 1500))
        assertEquals(AiOriginalPosition(9, 1500), views.restore(chapter))
        assertEquals(output, views.saved(chapter)?.output)
    }

    @Test
    fun duplicateResultDeliveryDoesNotResetTheOriginalPositionOrRebuildTheDisplay() {
        val views = AiChapterTextViews<Any>()
        val initial = views.show(chapter, output, Any(), AiOriginalPosition(5, 600))
        val duplicate = views.show(chapter, output, Any(), AiOriginalPosition(0, 0))
        assertSame(initial, duplicate)
        assertEquals(AiOriginalPosition(5, 600), views.restore(chapter))
    }

    @Test
    fun regeneratingAnAlreadyDisplayedSummaryPreservesTheOriginalAnchorAndTruncationWarning() {
        val views = AiChapterTextViews<String>()
        views.show(chapter, output, "first", AiOriginalPosition(8, 800))
        val replacement = AiSummaryOutput("Different recap", truncated = true)
        views.show(chapter, replacement, "second", AiOriginalPosition(1, 40))
        assertEquals(replacement, views.displayed(chapter)?.output)
        assertEquals("second", views.displayed(chapter)?.content)
        assertEquals(AiOriginalPosition(8, 800), views.restore(chapter))
    }

    @Test
    fun sourceReloadsInvalidateOldSummaryTextAndPositionsWithoutAffectingOtherChapters() {
        val views = AiChapterTextViews<String>()
        val other = chapter.copy(chapterId = "chapter-5")
        views.show(chapter, output, "chapter-four-summary", AiOriginalPosition(2, 200))
        views.show(other, output, "chapter-five-summary", AiOriginalPosition(3, 300))
        assertNull(views.saved(chapter.copy(text = "Updated source text")))
        assertNull(views.originalPosition(chapter.bookId, chapter.chapterId))
        assertNull(views.saved(chapter))
        assertEquals("chapter-five-summary", views.displayed(other)?.content)
    }

    @Test
    fun booksWithTheSameChapterIdRemainIndependentAndInvalidOutputCannotEraseAValidSummary() {
        val views = AiChapterTextViews<String>()
        val other = chapter.copy(bookId = "another-novel")
        views.show(chapter, output, "first-book", AiOriginalPosition(1, 150))
        views.show(other, output, "second-book", AiOriginalPosition(3, 350))
        try {
            views.show(chapter, AiSummaryOutput(" "), "empty", AiOriginalPosition())
            fail("Blank output must not replace a readable summary")
        } catch (_: IllegalArgumentException) { }
        assertEquals("first-book", views.displayed(chapter)?.content)
        assertEquals(AiOriginalPosition(1, 150), views.restore(chapter))
        assertEquals("second-book", views.displayed(other)?.content)
        assertEquals(AiOriginalPosition(3, 350), views.restore(other))
    }
}
