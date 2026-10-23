package com.lagradost.quicknovel.util

import com.lagradost.quicknovel.ChapterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSearchTest {
    @Test
    fun matchesTitlesWithoutCaseSensitivityAndIgnoresOuterQueryWhitespace() {
        val chapters = listOf("Prologue", "Chapter 12: The Gate", "Chapter 13: Home")
        assertEquals(listOf(chapters[1]), filterChapterTitles(chapters, "  THE gate  ") { it })
    }

    @Test
    fun clearingSearchOrEnteringOnlyWhitespaceRestoresAllChapters() {
        val chapters = listOf("Prologue", "Chapter 1", "Chapter 2")
        for (query in listOf("", " ", "\t\n")) {
            assertEquals(chapters, filterChapterTitles(chapters, query) { it })
        }
    }

    @Test
    fun supportsSingleCharacterAndNonLatinSearches() {
        val chapters = listOf("第1章：山", "第2章：海", "Chapter 3")
        assertEquals(listOf(chapters[0]), filterChapterTitles(chapters, "山") { it })
        assertEquals(listOf(chapters[1]), filterChapterTitles(chapters, "2") { it })
    }

    @Test
    fun unrelatedQueryReturnsNoChaptersInsteadOfFuzzyMatches() {
        val chapters = listOf("Chapter 12: The Gate", "Chapter 13: Home")
        assertTrue(filterChapterTitles(chapters, "Chapter 99") { it }.isEmpty())
        assertTrue(filterChapterTitles(emptyList<String>(), "gate") { it }.isEmpty())
    }

    @Test
    fun narrowsEligibleChaptersWithoutChangingSortOrderOrChapterObjects() {
        val earlier = ChapterData("Chapter 12: The Gate", "/chapter/12")
        val later = ChapterData("Chapter 14: The Gate", "/chapter/14")
        // Existing status filters/sort supplied only these chapters in descending order.
        val eligible = listOf(later, earlier)
        val matches = filterChapterTitles(eligible, "gate") { it.name }
        assertEquals(eligible, matches)
        assertSame(later, matches[0])
        assertSame(earlier, matches[1])
        assertEquals(eligible, filterChapterTitles(eligible, "") { it.name })
    }

    @Test
    fun duplicatePickerTitlesRetainTheirOriginalChapterIndices() {
        val titles = listOf("Prologue", "The Gate", "Interlude", "The Gate").withIndex().toList()
        val matches = filterChapterTitles(titles, "gate") { it.value }
        assertEquals(listOf(1, 3), matches.map { it.index })
        // Selecting the second visible result must open chapter index 3, not index 1.
        assertEquals(3, matches[1].index)
        assertEquals(3, filterChapterTitles(titles, "") { it.value }[3].index)
    }

    @Test
    fun reversingFilteredPickerResultsKeepsDuplicateChapterIdentityAndCanRestoreOriginalOrder() {
        val titles = listOf("Prologue", "The Gate", "Interlude", "The Gate").withIndex().toList()
        val reversed = filterChapterTitles(titles, "gate", reverse = true) { it.value }
        assertEquals(listOf(3, 1), reversed.map { it.index })
        assertEquals(0, reversed.indexOfFirst { it.index == 3 })
        assertEquals(1, reversed[1].index) // Navigation still addresses the original chapter.
        assertEquals(listOf(1, 3), filterChapterTitles(titles, "gate", reverse = false) { it.value }.map { it.index })
        assertEquals(listOf(3, 2, 1, 0), filterChapterTitles(titles, "", reverse = true) { it.value }.map { it.index })
        assertEquals(listOf(0, 1, 2, 3), titles.map { it.index })
    }

    @Test
    fun reverseUsesChapterSequenceRatherThanLexicalTitleOrderAndPreservesSearchMatching() {
        val earlier = ChapterData("Chapter 2: Arrival", "/chapter/2")
        val later = ChapterData("Chapter 10: Arrival", "/chapter/10")
        val chapters = listOf(earlier, later)
        val reversed = filterChapterTitles(chapters, "  ARRIVAL  ", reverse = true) { it.name }
        assertSame(later, reversed[0])
        assertSame(earlier, reversed[1])
        assertEquals(chapters, filterChapterTitles(chapters, "", reverse = false) { it.name })
        assertTrue(filterChapterTitles(chapters, "missing", reverse = true) { it.name }.isEmpty())
    }
}
