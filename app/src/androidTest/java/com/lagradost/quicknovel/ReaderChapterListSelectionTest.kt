package com.lagradost.quicknovel

import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import com.lagradost.quicknovel.ui.ChapterListScrollPosition
import com.lagradost.quicknovel.ui.chapterScrollPosition
import com.lagradost.quicknovel.ui.restoreChapterScrollPosition
import com.lagradost.quicknovel.util.filterChapterTitles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ListView checked state must reach its rows, including reused views after filtering. */
@RunWith(AndroidJUnit4::class)
class ReaderChapterListSelectionTest {
    @Test
    fun currentChapterIsHighlightedWithoutARadioIndicatorThroughFilteringAndRowReuse() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            val titles = List(40) { "Chapter ${it + 1}" }.toMutableList().apply {
                this[1] = "At the Harbor"
                this[3] = "At the Harbor"
            }.withIndex().toList()
            val currentChapter = 3
            lateinit var list: ListView
            lateinit var adapter: ArrayAdapter<String>

            fun show(query: String, reverse: Boolean = false) {
                val visible = filterChapterTitles(titles, query, reverse) { it.value }
                adapter.setNotifyOnChange(false)
                adapter.clear()
                adapter.addAll(visible.map { it.value })
                adapter.notifyDataSetChanged()
                list.clearChoices()
                val selected = visible.indexOfFirst { it.index == currentChapter }
                if (selected >= 0) list.setItemChecked(selected, true)
                list.setSelection(0)
            }

            fun assertVisibleSelection(expected: Int?) {
                assertEquals(expected ?: ListView.INVALID_POSITION, list.checkedItemPosition)
                assertTrue(list.childCount > 0)
                for (offset in 0 until list.childCount) {
                    val row = list.getChildAt(offset) as CheckedTextView
                    assertNull(row.checkMarkDrawable)
                    assertTrue(row.background.isStateful)
                    assertEquals(list.firstVisiblePosition + offset == expected, row.isChecked)
                    assertEquals(row.isChecked, row.drawableState.contains(android.R.attr.state_checked))
                }
            }

            scenario.onActivity { activity ->
                list = ListView(activity).apply { choiceMode = AbsListView.CHOICE_MODE_SINGLE }
                adapter = ArrayAdapter(activity, R.layout.reader_chapter_item)
                list.adapter = adapter
                activity.setContentView(list)
                show("")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertVisibleSelection(3); show("Harbor") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                // Duplicate text must not highlight the earlier chapter with the same name.
                assertVisibleSelection(1)
                show("Harbor", reverse = true)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertVisibleSelection(0)
                show("Chapter 20")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertVisibleSelection(null)
                show("")
                list.setSelection(30)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertVisibleSelection(3)
                list.setSelection(0)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertVisibleSelection(3) }
        }
    }

    @Test
    fun reversingShowsTheOppositeEndWhilePreservingListPositionAndPartialRowOffset() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            val chapters = List(240) { "Chapter ${it + 1}" }
                .withIndex().toList()
            var visible = chapters
            var reverse = false
            lateinit var list: ListView
            lateinit var adapter: ArrayAdapter<String>
            lateinit var original: ChapterListScrollPosition
            val currentChapter = 3

            fun reverseOrder() {
                val scroll = list.chapterScrollPosition()!!
                reverse = !reverse
                visible = filterChapterTitles(chapters, "", reverse) { it.value }
                adapter.setNotifyOnChange(false)
                adapter.clear()
                adapter.addAll(visible.map { it.value })
                adapter.notifyDataSetChanged()
                list.clearChoices()
                list.setItemChecked(visible.indexOfFirst { it.index == currentChapter }, true)
                assertTrue(list.restoreChapterScrollPosition(scroll))
            }

            scenario.onActivity { activity ->
                list = ListView(activity).apply {
                    choiceMode = AbsListView.CHOICE_MODE_SINGLE
                    setPadding(0, 11, 0, 0)
                }
                adapter = ArrayAdapter(activity, R.layout.reader_chapter_item, visible.map { it.value }.toMutableList())
                list.adapter = adapter
                activity.setContentView(list)
                list.setItemChecked(currentChapter, true)
                list.setSelectionFromTop(0, 0)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals("Chapter 1", (list.getChildAt(0) as CheckedTextView).text.toString())
                reverseOrder()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(0, list.firstVisiblePosition)
                assertEquals("Chapter 240", (list.getChildAt(0) as CheckedTextView).text.toString())
                assertEquals(236, list.checkedItemPosition)
                reverseOrder()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals("Chapter 1", (list.getChildAt(0) as CheckedTextView).text.toString())
                list.setSelectionFromTop(40, -17)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                original = list.chapterScrollPosition()!!
                assertEquals(40, original.position)
                assertEquals(-17, original.offset)
                reverseOrder()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(original, list.chapterScrollPosition())
                assertEquals(40, list.firstVisiblePosition)
                assertEquals("Chapter 200", (list.getChildAt(0) as CheckedTextView).text.toString())
                assertEquals(236, list.checkedItemPosition)
                reverseOrder()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(original, list.chapterScrollPosition())
                assertEquals(40, list.firstVisiblePosition)
                assertEquals("Chapter 41", (list.getChildAt(0) as CheckedTextView).text.toString())
                assertEquals(currentChapter, list.checkedItemPosition)
            }
        }
    }
}
