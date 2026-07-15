package com.lagradost.quicknovel.ai

enum class GlossaryCategory {
    TITLE,
    CHARACTER,
    ALIAS,
    PLACE,
    ORGANIZATION,
    RANK,
    HONORIFIC,
    ABILITY,
    ITEM,
    SPECIES,
    OTHER
}

enum class GlossarySource {
    AI,
    USER
}

data class TranslationGlossaryEntry(
    val sourceText: String = "",
    val translatedText: String = "",
    val category: GlossaryCategory = GlossaryCategory.OTHER,
    val source: GlossarySource = GlossarySource.AI,
    val locked: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class TranslationGlossary(
    val version: Int = 1,
    val revision: Long = 0L,
    val entries: List<TranslationGlossaryEntry> = emptyList()
)

data class GlossaryCandidate(
    val source: String = "",
    val translation: String = "",
    val category: GlossaryCategory = GlossaryCategory.OTHER
)

data class TranslationRequest(
    val text: String,
    val targetLanguage: String,
    val novelTitle: String?,
    val chapterTitle: String?,
    val glossary: List<TranslationGlossaryEntry>
)

data class TranslationResult(
    val translatedText: String,
    val discoveredEntries: List<GlossaryCandidate> = emptyList()
)
