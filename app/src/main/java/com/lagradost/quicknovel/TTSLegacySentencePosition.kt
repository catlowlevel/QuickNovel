package com.lagradost.quicknovel

/**
 * Read-only migration for bookmarks saved before source-character positions were introduced. Never used for speech:
 * reproducing the old boundaries lets the new parser find the same text.
 */
internal fun legacyTtsSentenceCharacter(text: String, sentence: Int): Int? {
    if (sentence < 0) return null
    val boundaryText =
        text
            .replace(Regex("\\.([A-z])"), ",$1")
            .replace(Regex("([.:])([0-9])"), ",$2")
            .replace(Regex("(^|[ \"“‘'])(Dr|Mr|Mrs)\\. ([A-Z])"), "$1$2, $3")
    var cursor = 0
    var ordinal = 0
    while (cursor < text.length) {
        while (cursor < text.length && text[cursor] in " .,\n\"'’‘“”«»「」…[]") cursor++
        if (cursor >= text.length) return null
        val boundary = boundaryText.indexOfAny(charArrayOf('.', '\n', ';', '?', ':'), cursor)
        var end = if (boundary < 0) text.length else boundary + 1
        while (end > cursor && text[end - 1] == '\n') end--
        val speech = text.substring(cursor, end).map { if (it in "-<>_^«»「」—–¿*~\u200c") ' ' else it }.joinToString("")
        if (Regex("[A-z0-9]").containsMatchIn(speech)) {
            if (ordinal == sentence) return cursor
            ordinal++
        }
        cursor = end
        if (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
    }
    return null
}
