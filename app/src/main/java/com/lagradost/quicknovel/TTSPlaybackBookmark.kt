package com.lagradost.quicknovel

import java.security.MessageDigest

/** Persist narration independently of the reader's visible chapter and scroll position. */
internal data class TTSPlaybackBookmark(
    val chapter: Int,
    val sentence: Int,
    val chapterId: String,
    /** Character in the chapter before paragraph formatting; null for older saved positions. */
    val character: Int? = null,
) {
    fun resolveChapter(chapterIds: List<String>): Int? {
        if (chapter < 0 || sentence < 0 || chapterId.isBlank()) return null
        if (chapterIds.getOrNull(chapter) == chapterId) return chapter
        return chapterIds.indexOf(chapterId).takeIf { it >= 0 }
    }

    fun resolveSentence(sentenceCount: Int): Int? {
        if (sentence < 0 || sentenceCount <= 0) return null
        return sentence.coerceAtMost(sentenceCount - 1)
    }

    fun resolveSentence(
        lines: List<TTSHelper.TTSLine>,
        displayCharacter: (Int) -> Int,
        originalLines: List<TTSHelper.TTSLine>,
    ): Int? {
        if (sentence < 0) return null
        val source = character?.takeIf { it >= 0 } ?: originalLines.getOrNull(sentence)?.startChar
        return if (source != null) lines.sentenceAtCharacter(displayCharacter(source)) else resolveSentence(lines.size)
    }
}

/** Length-prefix identity parts so books with similar titles/providers cannot share a bookmark. */
internal fun ttsPlaybackBookKey(vararg identity: String): String {
    val source = identity.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun List<TTSHelper.TTSLine>.sentenceAtCharacter(character: Int): Int? {
    if (isEmpty()) return null
    return indexOfLast { it.startChar <= character }.coerceAtLeast(0)
}
