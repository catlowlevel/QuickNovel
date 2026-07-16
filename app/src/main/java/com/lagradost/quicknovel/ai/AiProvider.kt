package com.lagradost.quicknovel.ai

interface AiProvider {
    suspend fun summarize(text: String): String
    suspend fun translate(request: TranslationRequest): TranslationResult
    fun estimateSummarizeTokens(text: String): AiTokenEstimate
    fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate
    suspend fun getModels(): List<String>
}
