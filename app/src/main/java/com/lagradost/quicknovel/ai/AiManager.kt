package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.AiProviderType
import com.lagradost.quicknovel.AiSettings

object AiManager {
    fun getProvider(settings: AiSettings): AiProvider? {
        if (settings.apiKey.isBlank() && settings.customUrl.isBlank()) return null
        
        return when (settings.providerType) {
            AiProviderType.Gemini -> GeminiProvider(settings.apiKey, settings.model, settings.customUrl)
            AiProviderType.OpenAI -> OpenAiProvider(settings.apiKey, settings.model, settings.customUrl)
            AiProviderType.Claude -> ClaudeProvider(settings.apiKey, settings.model, settings.customUrl)
        }
    }
}
