package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val apiKey: String,
    private val model: String,
    private val customUrl: String = ""
) : AiProvider {
    data class GeminiRequest(
        @JsonProperty("contents") val contents: List<Content>
    )

    data class Content(
        @JsonProperty("role") val role: String = "user",
        @JsonProperty("parts") val parts: List<Part>
    )

    data class Part(
        @JsonProperty("text") val text: String
    )

    override suspend fun summarize(text: String): String {
        return generateContent(AiPromptBuilder.summaryUserMessage(text))
    }

    override suspend fun summarizeStream(text: String, onPartial: (String) -> Unit): String {
        return generateContentStream(AiPromptBuilder.summaryUserMessage(text), onPartial)
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val raw = generateContent(TranslationPromptBuilder.build(request))
        return parseTranslation(raw)
    }

    override suspend fun translateStream(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        val raw = generateContentStream(TranslationPromptBuilder.build(request)) { partial ->
            onPartial(TranslationResponseParser.extractPartialTranslatedText(partial) ?: partial)
        }
        return parseTranslation(raw)
    }

    override suspend fun suggestGlossaryTranslations(request: GlossarySuggestionRequest): List<GlossarySuggestion> {
        val raw = generateContent(GlossarySuggestionPromptBuilder.build(request))
        return TranslationResponseParser.parseGlossarySuggestions(raw)
    }

    override fun estimateSummarizeTokens(text: String): AiTokenEstimate {
        return estimateContent(AiPromptBuilder.summaryUserMessage(text))
    }

    override fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate {
        return estimateContent(TranslationPromptBuilder.build(request))
    }

    override fun estimateGlossarySuggestionTokens(request: GlossarySuggestionRequest): AiTokenEstimate {
        return estimateContent(GlossarySuggestionPromptBuilder.build(request))
    }

    private fun estimateContent(prompt: String): AiTokenEstimate {
        return AiTokenEstimator.estimateGeminiContent(selectedModel(), prompt)
    }

    private suspend fun generateContent(prompt: String): String {
        val selectedModel = selectedModel()
        
        val request = GeminiRequest(listOf(Content(role = "user", parts = listOf(Part(prompt)))))

        val response = if (customUrl.isNotBlank()) {
            val url = if (customUrl.contains(":generateContent")) {
                customUrl
            } else {
                val base = customUrl.removeSuffix("/")
                if (base.contains("/models/")) {
                    "$base:generateContent"
                } else {
                    "$base/models/$selectedModel:generateContent"
                }
            }
            app.post(
                url,
                headers = mapOf("x-goog-api-key" to apiKey),
                json = request,
                timeout = 300
            )
        } else {
            val apiVersion = if (selectedModel.contains("preview") || selectedModel.contains("beta")) "v1beta" else "v1"
            val url = "https://generativelanguage.googleapis.com/$apiVersion/models/$selectedModel:generateContent?key=$apiKey"
            app.post(
                url,
                json = request,
                timeout = 300
            )
        }

        if (!response.isSuccessful) {
            if (customUrl.isBlank()) {
                val selectedModel = model.ifBlank { "gemini-1.5-flash" }
                val apiVersion = if (selectedModel.contains("preview") || selectedModel.contains("beta")) "v1beta" else "v1"
                if (apiVersion == "v1") {
                    val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent?key=$apiKey"
                    val fallbackResponse = app.post(fallbackUrl, json = request, timeout = 300)
                    if (fallbackResponse.isSuccessful) {
                        return parseResponse(fallbackResponse.text)
                    }
                }
            }
            throw Exception("Gemini API error: ${response.code} ${response.text}")
        }

        return parseResponse(response.text)
    }

    private suspend fun generateContentStream(prompt: String, onPartial: (String) -> Unit): String {
        val selectedModel = selectedModel()
        val request = GeminiRequest(listOf(Content(role = "user", parts = listOf(Part(prompt)))))
        val url = streamUrl(selectedModel)
        val json = app.responseParser?.writeValueAsString(request)
            ?: throw Exception("No JSON parser configured")
        val builder = StringBuilder()
        val httpRequestBuilder = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
        if (customUrl.isNotBlank()) {
            httpRequestBuilder.header("x-goog-api-key", apiKey)
        }
        var lastProgressPost = 0L
        var lastPostedLength = 0
        app.baseClient.newBuilder().readTimeout(300L, TimeUnit.SECONDS).build()
            .newCall(httpRequestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini API error: ${response.code} ${response.body.string()}")
            }
            response.body.charStream().buffered().forEachLine { line ->
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") return@forEachLine
                val chunk = app.responseParser?.parseSafe(data, GeminiResponse::class)
                val delta = chunk?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!delta.isNullOrEmpty()) {
                    builder.append(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastProgressPost >= 250L) {
                        onPartial(builder.toString())
                        lastProgressPost = now
                        lastPostedLength = builder.length
                    }
                }
            }
        }
        if (builder.length != lastPostedLength) {
            onPartial(builder.toString())
        }
        return builder.toString()
    }

    private fun streamUrl(selectedModel: String): String {
        val base = if (customUrl.isNotBlank()) {
            val normalized = customUrl.removeSuffix("/")
            when {
                normalized.contains(":streamGenerateContent") -> normalized
                normalized.contains(":generateContent") -> normalized.replace(":generateContent", ":streamGenerateContent")
                normalized.contains("/models/") -> "$normalized:streamGenerateContent"
                else -> "$normalized/models/$selectedModel:streamGenerateContent"
            }
        } else {
            val apiVersion = if (selectedModel.contains("preview") || selectedModel.contains("beta")) "v1beta" else "v1"
            "https://generativelanguage.googleapis.com/$apiVersion/models/$selectedModel:streamGenerateContent?key=$apiKey"
        }
        return if (base.contains("?")) "$base&alt=sse" else "$base?alt=sse"
    }

    private fun parseTranslation(raw: String): TranslationResult {
        return try {
            TranslationResponseParser.parse(raw)
        } catch (e: Exception) {
            val fallback = if (customUrl.isNotBlank()) TranslationResponseParser.safeFallbackText(raw) else null
            if (fallback != null) {
                TranslationResult(fallback, emptyList())
            } else {
                throw e
            }
        }
    }

    private fun selectedModel(): String {
        return model.ifBlank {
            if (customUrl.isNotBlank()) "gemini-3-flash" else "gemini-1.5-flash"
        }
    }

    private fun parseResponse(json: String): String {
        return try {
            val res = app.responseParser?.parse(json, GeminiResponse::class)
            res?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                ?: throw Exception("No content in Gemini response")
        } catch (e: Exception) {
            logError(e)
            throw Exception("Failed to parse Gemini response: ${e.message}")
        }
    }

    override suspend fun getModels(): List<String> {
        return try {
            if (customUrl.isNotBlank()) {
                val modelsUrl = if (customUrl.contains("/models/")) {
                    customUrl.substringBefore("/models/") + "/models"
                } else if (customUrl.endsWith("/models")) {
                    customUrl
                } else {
                    "${customUrl.removeSuffix("/")}/models"
                }
                val response = app.get(
                    modelsUrl,
                    headers = mapOf("x-goog-api-key" to apiKey)
                )
                if (response.isSuccessful) {
                    try {
                        val res = app.responseParser?.parse(response.text, OpenCodeListModelsResponse::class)
                        val list = res?.data?.filter { it.id.startsWith("gemini-") }?.map { it.id } ?: emptyList()
                        if (list.isNotEmpty()) return list
                    } catch (e: Exception) {
                        // Ignore and try standard Google format
                    }
                    val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                    res?.models?.map { it.name.substringAfter("models/") } ?: emptyList()
                } else {
                    listOf("gemini-3-flash", "gemini-3.5-flash", "gemini-3.1-pro")
                }
            } else {
                val response = app.get("https://generativelanguage.googleapis.com/v1/models?key=$apiKey")
                if (response.isSuccessful) {
                    val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                    res?.models?.map { it.name.substringAfter("models/") } ?: emptyList()
                } else {
                    listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash")
                }
            }
        } catch (e: Exception) {
            logError(e)
            if (customUrl.isNotBlank()) {
                listOf("gemini-3-flash", "gemini-3.5-flash", "gemini-3.1-pro")
            } else {
                listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash")
            }
        }
    }

    data class GeminiResponse(
        @JsonProperty("candidates") val candidates: List<Candidate>?
    )

    data class Candidate(
        @JsonProperty("content") val content: Content?
    )

    data class ListModelsResponse(
        @JsonProperty("models") val models: List<GeminiModel>?
    )

    data class GeminiModel(
        @JsonProperty("name") val name: String
    )

    data class OpenCodeListModelsResponse(
        @JsonProperty("data") val data: List<OpenCodeModel>?
    )

    data class OpenCodeModel(
        @JsonProperty("id") val id: String
    )
}
