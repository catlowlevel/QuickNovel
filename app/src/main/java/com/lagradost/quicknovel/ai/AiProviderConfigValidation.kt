package com.lagradost.quicknovel.ai

internal fun validatedAiConfig(
    provider: AiProvider,
    config: AiProviderConfig,
    requireModel: Boolean,
): AiProviderConfig {
    val baseUrl = config.baseUrl.trim()
    if (baseUrl.isNotEmpty() && aiApiBaseUrlOrNull(baseUrl) == null) throw AiProviderException(AiFailure.INVALID_URL)
    val key = config.apiKey.trim()
    if (key.isEmpty() && baseUrl.isEmpty()) throw AiProviderException(AiFailure.MISSING_KEY)
    if (key.any { it <= ' ' || it > '~' }) throw AiProviderException(AiFailure.INVALID_KEY)
    val model = config.model.trim().let { if (provider == AiProvider.GEMINI) it.removePrefix("models/") else it }
    if (requireModel) {
        if (model.isEmpty()) throw AiProviderException(AiFailure.MISSING_MODEL)
        if (model == "." || model == ".." || model.any { it.isWhitespace() || it.isISOControl() }) {
            throw AiProviderException(AiFailure.INVALID_MODEL)
        }
    }
    return config.copy(apiKey = key, model = model, baseUrl = baseUrl)
}
