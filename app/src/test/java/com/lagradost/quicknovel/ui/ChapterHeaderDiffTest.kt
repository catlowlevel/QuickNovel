package com.lagradost.quicknovel.ui

import com.lagradost.quicknovel.ChapterStartSpanned
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterHeaderDiffTest {
    private val diff = TextAdapter.DiffCallback()
    private val header = ChapterStartSpanned(2, 0, UiText.DynamicString("Chapter 3"), true)

    @Test
    fun loadingACountUpdatesTheExistingHeaderIncludingARealZero() {
        for (count in listOf(0, 250)) {
            val loaded = header.copy(wordCount = count)
            assertTrue(diff.areItemsTheSame(header, loaded))
            assertFalse(diff.areContentsTheSame(header, loaded))
        }
    }

    @Test
    fun reloadOrTranslationChangesRefreshTheCountWithoutReplacingTheHeader() {
        val original = header.copy(wordCount = 100)
        val translated = header.copy(wordCount = 125)
        assertTrue(diff.areItemsTheSame(original, translated))
        assertFalse(diff.areContentsTheSame(original, translated))
        assertFalse(diff.areContentsTheSame(original, header))
        assertTrue(diff.areContentsTheSame(original, original.copy()))
    }

    @Test
    fun switchingBetweenOriginalAndSummaryRefreshesTheBadgeEvenWithTheSameTitleAndCount() {
        val original = header.copy(wordCount = 400)
        val summary = original.copy(showingSummary = true)
        assertTrue(diff.areItemsTheSame(original, summary))
        assertFalse(diff.areContentsTheSame(original, summary))
        assertFalse(diff.areContentsTheSame(summary, original))
        assertTrue(diff.areContentsTheSame(summary, summary.copy()))
    }

    @Test
    fun regeneratedCompleteAndIncompleteSummariesRefreshTheWarningWithoutChangingIdentity() {
        val complete = header.copy(wordCount = 400, showingSummary = true)
        val incomplete = complete.copy(summaryTruncated = true)
        assertTrue(diff.areItemsTheSame(complete, incomplete))
        assertFalse(diff.areContentsTheSame(complete, incomplete))
        assertFalse(diff.areContentsTheSame(incomplete, complete))
    }
}
