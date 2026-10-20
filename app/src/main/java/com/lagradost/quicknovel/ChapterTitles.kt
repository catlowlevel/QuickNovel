package com.lagradost.quicknovel

import org.jsoup.parser.Parser

/** Decode entities once without interpreting title text as HTML or changing stored metadata. */
internal fun chapterTitleText(title: String): String = Parser.unescapeEntities(title, true)

internal val ChapterData.displayName: String get() = chapterTitleText(name)
