package com.lagradost.quicknovel

/** UTF-16 offsets within one displayed paragraph; narration offsets stay in original chapter text. */
internal data class TTSBlurRange(val start: Int, val end: Int)

internal fun upcomingTTSBlurRange(
    enabled: Boolean,
    status: TTSHelper.TTSStatus,
    current: TTSHelper.TTSLine?,
    chapter: Int,
    startChar: Int,
    endChar: Int,
): TTSBlurRange? {
    if (!enabled || status == TTSHelper.TTSStatus.IsStopped || current == null || endChar <= startChar) return null
    if (chapter < current.index) return null
    val start = if (chapter > current.index) startChar else maxOf(startChar, current.endChar)
    return if (start < endChar) TTSBlurRange(start - startChar, endChar - startChar) else null
}
