package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError

class OpenAiProvider(
    private val apiKey: String, 
    private val model: String,
    private val customUrl: String = ""
) : AiProvider {
    
    data class ChatRequest(
        @JsonProperty("model") val model: String,
        @JsonProperty("messages") val messages: List<Message>
    )

    data class Message(
        @JsonProperty("role") val role: String,
        @JsonProperty("content") val content: String
    )

    override suspend fun summarize(text: String): String {
        val prompt = "Condense the following novel chapter to be significantly shorter while retaining all key plot points, essential character dialogue, and the overall atmosphere. The goal is a detailed reduction, not a brief summary. Provide only the condensed text. Chapter text:\n\n$text"
        return chatCompletion(
            systemMessage = "You are an expert editor that condenses novel chapters while preserving their essence, key dialogue, and plot progression.",
            userMessage = prompt
        )
    }

    override suspend fun translate(text: String, targetLanguage: String): String {
        val prompt = "Translate the following novel chapter into $targetLanguage. Maintain the original atmosphere, tone, and formatting. Pay special attention to character names, place names, and unique terminology, ensuring they are translated consistently and correctly. If a name has a common translation, use it; otherwise, transliterate it appropriately. Provide only the translated text. Chapter text:\n\n$text"
        return chatCompletion(
            systemMessage = "You are an expert translator that translates novel chapters while preserving their essence, tone, and character names.",
            userMessage = prompt
        )
    }

    private suspend fun chatCompletion(systemMessage: String, userMessage: String): String {
        val selectedModel = model.ifBlank {
            if (customUrl.isNotBlank()) "gpt-5-nano" else "gpt-5.4-mini"
        }
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
            timeout = 120
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

    data class ListModelsResponse(
        @JsonProperty("data") val data: List<OpenAiModel>?
    )

    data class OpenAiModel(
        @JsonProperty("id") val id: String
    )
}
