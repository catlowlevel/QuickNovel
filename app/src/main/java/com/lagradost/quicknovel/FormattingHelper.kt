package com.lagradost.quicknovel

object FormattingHelper {
    val sentenceEndChars = setOf('.', '!', '?', '…', '。', '！', '？')
    val quoteChars = setOf('"', '“', '”', '‟', '„', '\'', '’', '‘', '»', '«', '」', '「', '』', '『')

    fun getSemanticLastChar(text: String): Char? {
        val ignoreTrailing = setOf(
            '"', '“', '”', '‟', '„', '\'', '’', '‘', '»', '«', '」', '「', '』', '『', ')', ']', '}',
            ' ', '\t', '\n', '\r'
        )
        var i = text.length - 1
        while (i >= 0) {
            val c = text[i]
            if (c !in ignoreTrailing) {
                return c
            }
            i--
        }
        return null
    }

    fun getSemanticFirstText(text: String): String {
        val ignoreLeading = setOf(
            '"', '“', '”', '‟', '„', '\'', '’', '‘', '»', '«', '」', '「', '』', '『', '(', '[', '{',
            ' ', '\t', '\n', '\r'
        )
        var start = 0
        while (start < text.length && text[start] in ignoreLeading) {
            start++
        }
        return text.substring(start)
    }
}
