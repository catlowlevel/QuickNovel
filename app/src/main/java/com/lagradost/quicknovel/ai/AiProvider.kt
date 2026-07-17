package com.lagradost.quicknovel.ai

interface AiProvider {
    suspend fun summarize(text: String): String
    suspend fun translate(request: TranslationRequest): TranslationResult
    suspend fun suggestGlossaryTranslations(request: GlossarySuggestionRequest): List<GlossarySuggestion>
    fun estimateSummarizeTokens(text: String): AiTokenEstimate
    fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate
    fun estimateGlossarySuggestionTokens(request: GlossarySuggestionRequest): AiTokenEstimate
    suspend fun getModels(): List<String>
}
