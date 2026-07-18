package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiProvider(
    private val apiKey: String, 
    private val model: String,
    private val customUrl: String = ""
) : AiProvider {
    
    data class ChatRequest(
        @JsonProperty("model") val model: String,
        @JsonProperty("messages") val messages: List<Message>,
        @JsonProperty("stream") val stream: Boolean = false
    )

    data class Message(
        @JsonProperty("role") val role: String,
        @JsonProperty("content") val content: String
    )

    override suspend fun summarize(text: String): String {
        return chatCompletion(
            systemMessage = AiPromptBuilder.summarySystemMessage(),
            userMessage = AiPromptBuilder.summaryUserMessage(text)
        )
    }

    override suspend fun summarizeStream(text: String, onPartial: (String) -> Unit): String {
        return chatCompletionStream(
            systemMessage = AiPromptBuilder.summarySystemMessage(),
            userMessage = AiPromptBuilder.summaryUserMessage(text),
            onPartial = onPartial
        )
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val raw = chatCompletion(
            systemMessage = TranslationPromptBuilder.systemMessage(request.targetLanguage),
            userMessage = TranslationPromptBuilder.build(request)
        )
        return parseTranslation(raw)
    }

    override suspend fun translateStream(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        val raw = chatCompletionStream(
            systemMessage = TranslationPromptBuilder.systemMessage(request.targetLanguage),
            userMessage = TranslationPromptBuilder.build(request)
        ) { partial ->
            onPartial(TranslationResponseParser.extractPartialTranslatedText(partial) ?: partial)
        }
        return parseTranslation(raw)
    }

    override suspend fun suggestGlossaryTranslations(request: GlossarySuggestionRequest): List<GlossarySuggestion> {
        val raw = chatCompletion(
            systemMessage = "You suggest concise literary translation glossary terms. Return only valid JSON.",
            userMessage = GlossarySuggestionPromptBuilder.build(request)
        )
        return TranslationResponseParser.parseGlossarySuggestions(raw)
    }

    override suspend fun suggestRawGlossaryTerms(request: RawGlossaryTermRequest): List<RawGlossaryTermCandidate> {
        val raw = chatCompletion(
            systemMessage = "You infer possible original glossary terms from translations. Return only valid JSON.",
            userMessage = RawGlossaryTermPromptBuilder.build(request)
        )
        return TranslationResponseParser.parseRawGlossaryTermCandidates(raw)
    }

    override fun estimateSummarizeTokens(text: String): AiTokenEstimate {
        return estimateChatCompletion(
            systemMessage = AiPromptBuilder.summarySystemMessage(),
            userMessage = AiPromptBuilder.summaryUserMessage(text)
        )
    }

    override fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate {
        return estimateChatCompletion(
            systemMessage = TranslationPromptBuilder.systemMessage(request.targetLanguage),
            userMessage = TranslationPromptBuilder.build(request)
        )
    }

    override fun estimateGlossarySuggestionTokens(request: GlossarySuggestionRequest): AiTokenEstimate {
        return estimateChatCompletion(
            systemMessage = "You suggest concise literary translation glossary terms. Return only valid JSON.",
            userMessage = GlossarySuggestionPromptBuilder.build(request)
        )
    }

    override fun estimateRawGlossaryTermTokens(request: RawGlossaryTermRequest): AiTokenEstimate {
        return estimateChatCompletion(
            systemMessage = "You infer possible original glossary terms from translations. Return only valid JSON.",
            userMessage = RawGlossaryTermPromptBuilder.build(request)
        )
    }

    private fun estimateChatCompletion(systemMessage: String, userMessage: String): AiTokenEstimate {
        return AiTokenEstimator.estimateOpenAiChat(
            selectedModel(),
            listOf("system" to systemMessage, "user" to userMessage)
        )
    }

    private suspend fun chatCompletion(systemMessage: String, userMessage: String): String {
        val selectedModel = selectedModel()
        val baseUrl = if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/chat/completions")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/chat/completions"
            }
        } else {
            "https://api.openai.com/v1/chat/completions"
        }
        
        val request = ChatRequest(
            model = selectedModel,
            messages = listOf(
                Message(role = "system", content = systemMessage),
                Message(role = "user", content = userMessage)
            )
        )

        val response = app.post(
            baseUrl,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            json = request,
            timeout = 300
        )

        if (!response.isSuccessful) {
            throw Exception("OpenAI API error: ${response.code} ${response.text}")
        }

        return try {
            val res = app.responseParser?.parse(response.text, ChatResponse::class)
            res?.choices?.firstOrNull()?.message?.content 
                ?: throw Exception("No content in OpenAI response")
        } catch (e: Exception) {
            logError(e)
            throw Exception("Failed to parse OpenAI response: ${e.message}")
        }
    }

    private suspend fun chatCompletionStream(
        systemMessage: String,
        userMessage: String,
        onPartial: (String) -> Unit
    ): String {
        val selectedModel = selectedModel()
        val baseUrl = chatCompletionsUrl()
        val request = ChatRequest(
            model = selectedModel,
            messages = listOf(
                Message(role = "system", content = systemMessage),
                Message(role = "user", content = userMessage)
            ),
            stream = true
        )
        val json = app.responseParser?.writeValueAsString(request)
            ?: throw Exception("No JSON parser configured")
        val httpRequest = Request.Builder()
            .url(baseUrl)
            .headers(okhttp3.Headers.headersOf("Authorization", "Bearer $apiKey"))
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val builder = StringBuilder()
        var lastProgressPost = 0L
        var lastPostedLength = 0
        app.baseClient.newBuilder().readTimeout(300L, TimeUnit.SECONDS).build()
            .newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI API error: ${response.code} ${response.body.string()}")
            }
            response.body.charStream().buffered().forEachLine { line ->
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") return@forEachLine
                val chunk = app.responseParser?.parseSafe(data, ChatStreamResponse::class)
                val delta = chunk?.choices?.firstOrNull()?.delta?.content
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

    private fun chatCompletionsUrl(): String {
        return if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/chat/completions")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/chat/completions"
            }
        } else {
            "https://api.openai.com/v1/chat/completions"
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
            if (customUrl.isNotBlank()) "gpt-5-nano" else "gpt-5.4-mini"
        }
    }

    override suspend fun getModels(): List<String> {
        val modelsUrl = if (customUrl.isNotBlank()) {
            if (customUrl.endsWith("/chat/completions")) {
                customUrl.substringBefore("/chat/completions") + "/models"
            } else if (customUrl.endsWith("/models")) {
                customUrl
            } else {
                "${customUrl.removeSuffix("/")}/models"
            }
        } else {
            "https://api.openai.com/v1/models"
        }
        return try {
            val response = app.get(
                modelsUrl,
                headers = mapOf("Authorization" to "Bearer $apiKey")
            )
            if (response.isSuccessful) {
                val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                res?.data?.filter { 
                    if (customUrl.isNotBlank()) {
                        !it.id.contains("vision") && !it.id.contains("audio")
                    } else {
                        it.id.startsWith("gpt-") && !it.id.contains("vision") && !it.id.contains("audio")
                    }
                }?.map { it.id }?.sortedDescending() ?: emptyList()
            } else {
                if (customUrl.isNotBlank()) {
                    listOf("gpt-5-nano", "gpt-5.4-nano", "gpt-5.4-mini", "gpt-5.5")
                } else {
                    listOf("gpt-5.4-mini", "gpt-5.5", "gpt-5.4-pro", "gpt-4o-mini")
                }
            }
        } catch (e: Exception) {
            logError(e)
            if (customUrl.isNotBlank()) {
                listOf("gpt-5-nano", "gpt-5.4-nano", "gpt-5.4-mini", "gpt-5.5")
            } else {
                listOf("gpt-5.4-mini", "gpt-5.5", "gpt-5.4-pro", "gpt-4o-mini")
            }
        }
    }

    data class ChatResponse(
        @JsonProperty("choices") val choices: List<Choice>?
    )

    data class Choice(
        @JsonProperty("message") val message: Message?
    )

    data class ChatStreamResponse(
        @JsonProperty("choices") val choices: List<StreamChoice>?
    )

    data class StreamChoice(
        @JsonProperty("delta") val delta: StreamDelta?
    )

    data class StreamDelta(
        @JsonProperty("content") val content: String?
    )

    data class ListModelsResponse(
        @JsonProperty("data") val data: List<OpenAiModel>?
    )

    data class OpenAiModel(
        @JsonProperty("id") val id: String
    )
}
