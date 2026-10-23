package com.lagradost.quicknovel.util

/** Filter/reverse presentation order without changing original chapter identities. */
internal fun <T> filterChapterTitles(chapters: List<T>, query: String, reverse: Boolean = false, title: (T) -> String): List<T> {
    val text = query.trim()
    val matches = if (text.isEmpty()) chapters else chapters.filter { title(it).contains(text, ignoreCase = true) }
    return if (reverse) matches.reversed() else matches
}
