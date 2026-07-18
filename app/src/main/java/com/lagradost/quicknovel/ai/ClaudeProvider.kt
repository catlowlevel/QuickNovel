package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ClaudeProvider(
    private val apiKey: String,
    private val model: String,
    private val customUrl: String = ""
) : AiProvider {
    data class ClaudeRequest(
        @JsonProperty("model") val model: String,
        @JsonProperty("max_tokens") val maxTokens: Int = 4096,
        @JsonProperty("messages") val messages: List<Message>,
        @JsonProperty("stream") val stream: Boolean = false
    )

    data class Message(
        @JsonProperty("role") val role: String,
        @JsonProperty("content") val content: String
    )

    override suspend fun summarize(text: String): String {
        return sendMessage(AiPromptBuilder.summaryUserMessage(text))
    }

    override suspend fun summarizeStream(text: String, onPartial: (String) -> Unit): String {
        return sendMessageStream(AiPromptBuilder.summaryUserMessage(text), onPartial)
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val raw = sendMessage(TranslationPromptBuilder.build(request))
        return parseTranslation(raw)
    }

    override suspend fun translateStream(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        val raw = sendMessageStream(TranslationPromptBuilder.build(request)) { partial ->
            onPartial(TranslationResponseParser.extractPartialTranslatedText(partial) ?: partial)
        }
        return parseTranslation(raw)
    }

    override suspend fun suggestGlossaryTranslations(request: GlossarySuggestionRequest): List<GlossarySuggestion> {
        val raw = sendMessage(GlossarySuggestionPromptBuilder.build(request))
        return TranslationResponseParser.parseGlossarySuggestions(raw)
    }

    override suspend fun suggestRawGlossaryTerms(request: RawGlossaryTermRequest): List<RawGlossaryTermCandidate> {
        val raw = sendMessage(RawGlossaryTermPromptBuilder.build(request))
        return TranslationResponseParser.parseRawGlossaryTermCandidates(raw)
    }

    override fun estimateSummarizeTokens(text: String): AiTokenEstimate {
        return estimateMessage(AiPromptBuilder.summaryUserMessage(text))
    }

    override fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate {
        return estimateMessage(TranslationPromptBuilder.build(request))
    }

    override fun estimateGlossarySuggestionTokens(request: GlossarySuggestionRequest): AiTokenEstimate {
        return estimateMessage(GlossarySuggestionPromptBuilder.build(request))
    }

    override fun estimateRawGlossaryTermTokens(request: RawGlossaryTermRequest): AiTokenEstimate {
        return estimateMessage(RawGlossaryTermPromptBuilder.build(request))
    }

    private fun estimateMessage(prompt: String): AiTokenEstimate {
        return AiTokenEstimator.estimateClaudeMessage(selectedModel(), prompt)
    }

    private suspend fun sendMessage(prompt: String): String {
        val selectedModel = selectedModel()
        val url = if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/messages")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/messages"
            }
        } else {
            "https://api.anthropic.com/v1/messages"
        }
        
        val request = ClaudeRequest(
            model = selectedModel,
            messages = listOf(Message(role = "user", content = prompt))
        )

        val response = app.post(
            url,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "content-type" to "application/json"
            ),
            json = request,
            timeout = 300
        )

        if (!response.isSuccessful) {
            throw Exception("Claude API error: ${response.code} ${response.text}")
        }

        return try {
            val res = app.responseParser?.parse(response.text, ClaudeResponse::class)
            res?.content?.firstOrNull()?.text 
                ?: throw Exception("No content in Claude response")
        } catch (e: Exception) {
            logError(e)
            throw Exception("Failed to parse Claude response: ${e.message}")
        }
    }

    private suspend fun sendMessageStream(prompt: String, onPartial: (String) -> Unit): String {
        val selectedModel = selectedModel()
        val request = ClaudeRequest(
            model = selectedModel,
            messages = listOf(Message(role = "user", content = prompt)),
            stream = true
        )
        val json = app.responseParser?.writeValueAsString(request)
            ?: throw Exception("No JSON parser configured")
        val httpRequest = Request.Builder()
            .url(messagesUrl())
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val builder = StringBuilder()
        var lastProgressPost = 0L
        var lastPostedLength = 0
        app.baseClient.newBuilder().readTimeout(300L, TimeUnit.SECONDS).build()
            .newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Claude API error: ${response.code} ${response.body.string()}")
            }
            response.body.charStream().buffered().forEachLine { line ->
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") return@forEachLine
                val chunk = app.responseParser?.parseSafe(data, ClaudeStreamResponse::class)
                val delta = chunk?.delta?.text
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

    private fun messagesUrl(): String {
        return if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/messages")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/messages"
            }
        } else {
            "https://api.anthropic.com/v1/messages"
        }
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
            if (customUrl.isNotBlank()) "claude-haiku-4-5" else "claude-sonnet-4-6"
        }
    }

    override suspend fun getModels(): List<String> {
        val modelsUrl = if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/messages")) {
                customUrl.substringBefore("/messages") + "/models"
            } else if (customUrl.endsWith("/models")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/models"
            }
        } else {
            "https://api.anthropic.com/v1/models"
        }
        return try {
            val response = app.get(
                modelsUrl,
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
            )
            if (response.isSuccessful) {
                val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                res?.data?.filter {
                    if (customUrl.isNotBlank()) it.id.startsWith("claude-") else true
                }?.map { it.id } ?: emptyList()
            } else {
                if (customUrl.isNotBlank()) {
                    listOf("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7")
                } else {
                    listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-haiku-4-5")
                }
            }
        } catch (e: Exception) {
            logError(e)
            if (customUrl.isNotBlank()) {
                listOf("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7")
            } else {
                listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-haiku-4-5")
            }
        }
    }

    data class ClaudeResponse(
        @JsonProperty("content") val content: List<ContentPart>?
    )

    data class ContentPart(
        @JsonProperty("text") val text: String?
    )

    data class ClaudeStreamResponse(
        @JsonProperty("delta") val delta: ClaudeStreamDelta?
    )

    data class ClaudeStreamDelta(
        @JsonProperty("text") val text: String?
    )

    data class ListModelsResponse(
        @JsonProperty("data") val data: List<ClaudeModel>?
    )

    data class ClaudeModel(
        @JsonProperty("id") val id: String
    )
}
