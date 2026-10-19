package com.lagradost.quicknovel.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** API version/path prefixes belong to the configured base; request paths are appended to it. */
internal fun aiApiBaseUrlOrNull(value: String): HttpUrl? {
    val raw = value.trim()
    if (raw.isEmpty() || raw.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = raw.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
}
