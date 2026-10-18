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
}
