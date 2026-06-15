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

    fun isHeader(text: String): Boolean {
        val trimmed = text.trim()
        val clean = trimmed.removeSurrounding("\"").removeSurrounding("“").removeSurrounding("”").trim()
        if (clean.isEmpty()) return false
        
        val words = clean.split(Regex("\\s+"))
        if (words.isEmpty()) return false

        // A single word with quotes (like "Yes", "No") is dialogue, not a header
        val hasQuotes = trimmed.startsWith("\"") || trimmed.startsWith("“") || trimmed.startsWith("‘")
        if (words.size < 2 && hasQuotes) return false
        
        val lowercaseExceptions = setOf(
            "a", "an", "the", "and", "but", "or", "for", "nor", "on", "in", "at", "to", "by", "of", "with"
        )
        
        var looksLikeHeader = true
        for (word in words) {
            val wordClean = word.filter { it.isLetter() }
            if (wordClean.isEmpty()) continue
            
            val firstChar = wordClean[0]
            if (!firstChar.isUpperCase() && wordClean.lowercase() !in lowercaseExceptions) {
                looksLikeHeader = false
                break
            }
        }
        
        return looksLikeHeader && words.size <= 10
    }
}
