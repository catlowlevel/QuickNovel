package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError

class ClaudeProvider(
    private val apiKey: String,
    private val model: String,
    private val customUrl: String = ""
) : AiProvider {
    data class ClaudeRequest(
        @JsonProperty("model") val model: String,
        @JsonProperty("max_tokens") val maxTokens: Int = 4096,
        @JsonProperty("messages") val messages: List<Message>
    )

    data class Message(
        @JsonProperty("role") val role: String,
        @JsonProperty("content") val content: String
    )

    override suspend fun summarize(text: String): String {
        return sendMessage(AiPromptBuilder.summaryUserMessage(text))
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val raw = sendMessage(TranslationPromptBuilder.build(request))
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

    override fun estimateSummarizeTokens(text: String): AiTokenEstimate {
        return estimateMessage(AiPromptBuilder.summaryUserMessage(text))
    }

    override fun estimateTranslateTokens(request: TranslationRequest): AiTokenEstimate {
        return estimateMessage(TranslationPromptBuilder.build(request))
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

    data class ListModelsResponse(
        @JsonProperty("data") val data: List<ClaudeModel>?
    )

    data class ClaudeModel(
        @JsonProperty("id") val id: String
    )
}
