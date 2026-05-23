package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError

class ClaudeProvider(private val apiKey: String, private val model: String) : AiProvider {
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
        val prompt = "Condense the following novel chapter to be significantly shorter while retaining all key plot points, essential character dialogue, and the overall atmosphere. The goal is a detailed reduction, not a brief summary. Provide only the condensed text. Chapter text:\n\n$text"
        return sendMessage(prompt)
    }

    override suspend fun translate(text: String, targetLanguage: String): String {
        val prompt = "Translate the following novel chapter into $targetLanguage. Maintain the original atmosphere, tone, and formatting. Pay special attention to character names, place names, and unique terminology, ensuring they are translated consistently and correctly. If a name has a common translation, use it; otherwise, transliterate it appropriately. Provide only the translated text. Chapter text:\n\n$text"
        return sendMessage(prompt)
    }

    private suspend fun sendMessage(prompt: String): String {
        val selectedModel = model.ifBlank { "claude-sonnet-4-6" }
        val url = "https://api.anthropic.com/v1/messages"
        
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
            timeout = 120
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

    override suspend fun getModels(): List<String> {
        return try {
            val response = app.get(
                "https://api.anthropic.com/v1/models",
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                )
            )
            if (response.isSuccessful) {
                val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                res?.data?.map { it.id } ?: emptyList()
            } else {
                listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-haiku-4-5")
            }
        } catch (e: Exception) {
            logError(e)
            listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-haiku-4-5")
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
