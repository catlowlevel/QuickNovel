package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.AiProviderType
import com.lagradost.quicknovel.AiSettings

object AiManager {
    fun getProvider(settings: AiSettings): AiProvider? {
        if (settings.apiKey.isBlank() && settings.providerType != AiProviderType.Custom) return null
        
        return when (settings.providerType) {
            AiProviderType.Gemini -> GeminiProvider(settings.apiKey, settings.model)
            AiProviderType.OpenAI -> OpenAiProvider(settings.apiKey, settings.model)
            AiProviderType.Claude -> ClaudeProvider(settings.apiKey, settings.model)
            AiProviderType.Custom -> OpenAiProvider(settings.apiKey, settings.model, settings.customUrl)
        }
    }
}
