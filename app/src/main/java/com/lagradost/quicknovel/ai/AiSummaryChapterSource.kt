package com.lagradost.quicknovel.ai

import kotlinx.coroutines.flow.StateFlow

sealed interface AiNextSummaryChapter {
    data class Loaded(val chapter: AiChapter) : AiNextSummaryChapter
    data class Waiting(val title: String) : AiNextSummaryChapter
    data object End : AiNextSummaryChapter
    data object SourceChanged : AiNextSummaryChapter
}

/** Reads already loaded original text; it must never fetch or navigate chapters itself. */
interface AiSummaryChapterSource {
    val changes: StateFlow<Long>
    suspend fun next(after: AiChapter): AiNextSummaryChapter
    suspend fun loadedAfter(after: AiChapter): List<AiChapter>
}

enum class AiSummaryFinishReason { END, CACHE_WRITE_FAILED, INCOMPLETE }
