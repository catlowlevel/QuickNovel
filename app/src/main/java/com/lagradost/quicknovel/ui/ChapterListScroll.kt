package com.lagradost.quicknovel.ui

import android.widget.ListView

internal data class ChapterListScrollPosition(val position: Int, val offset: Int)

/** Keep the list viewport fixed while its chapter order reverses underneath it. */
internal fun ListView.chapterScrollPosition(): ChapterListScrollPosition? {
    val first = getChildAt(0) ?: return null
    if (firstVisiblePosition !in 0 until count) return null
    return ChapterListScrollPosition(firstVisiblePosition, first.top - listPaddingTop)
}

internal fun ListView.restoreChapterScrollPosition(scroll: ChapterListScrollPosition): Boolean {
    if (scroll.position !in 0 until count) return false
    setSelectionFromTop(scroll.position, scroll.offset)
    return true
}
