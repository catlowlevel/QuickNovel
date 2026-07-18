package com.lagradost.quicknovel.ai

interface AiProvider {
    suspend fun summarize(text: String): String
    suspend fun summarizeStream(text: String, onPartial: (String) -> Unit): String {
        return summarize(text).also(onPartial)
    }

    suspend fun translate(request: TranslationRequest): TranslationResult
    suspend fun translateStream(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        return translate(request).also { onPartial(it.translatedText) }
    }

    suspend fun suggestGlossaryTranslations(request: GlossarySuggestionRequest): List<GlossarySuggestion>
    suspend fun suggestRawGlossaryTerms(request: RawGlossaryTermRequest): List<RawGlossaryTermCandidate>
    fun estimateSummarizeTokens(text: String): AiTokenEstimate
    fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate
    fun estimateGlossarySuggestionTokens(request: GlossarySuggestionRequest): AiTokenEstimate
    fun estimateRawGlossaryTermTokens(request: RawGlossaryTermRequest): AiTokenEstimate
    suspend fun getModels(): List<String>
}
