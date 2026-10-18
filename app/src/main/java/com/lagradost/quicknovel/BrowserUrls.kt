package com.lagradost.quicknovel

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only web pages belong in the embedded browser; app/file/script intents are never loaded. */
internal fun browserUrlOrNull(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
    val url = raw.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString()
}

/** Resolve the requested chapter, including failed/preloaded rows, rather than the visible one. */
internal fun chapterBrowserUrlOrNull(
    chapters: List<ChapterData>,
    index: Int,
    resolveUrl: (String) -> String,
): String? {
    val chapter = chapters.getOrNull(index)?.url?.takeIf { it.isNotBlank() } ?: return null
    return browserUrlOrNull(runCatching { resolveUrl(chapter) }.getOrNull())
}
