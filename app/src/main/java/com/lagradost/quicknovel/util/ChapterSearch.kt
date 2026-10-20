package com.lagradost.quicknovel.util

/** Retains the original items and their order so filtering cannot change chapter identity. */
internal fun <T> filterChapterTitles(chapters: List<T>, query: String, title: (T) -> String): List<T> {
    val text = query.trim()
    if (text.isEmpty()) return chapters
    return chapters.filter { title(it).contains(text, ignoreCase = true) }
}
