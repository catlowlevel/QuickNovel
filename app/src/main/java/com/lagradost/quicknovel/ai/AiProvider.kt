package com.lagradost.quicknovel.ai

interface AiProvider {
    suspend fun summarize(text: String): String
    suspend fun translate(request: TranslationRequest): TranslationResult
    suspend fun getModels(): List<String>
}
