package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError

class GeminiProvider(private val apiKey: String, private val model: String) : AiProvider {
    data class GeminiRequest(
        @JsonProperty("contents") val contents: List<Content>
    )

    data class Content(
        @JsonProperty("parts") val parts: List<Part>
    )

    data class Part(
        @JsonProperty("text") val text: String
    )

    override suspend fun summarize(text: String): String {
        val prompt = "Condense the following novel chapter to be significantly shorter while retaining all key plot points, essential character dialogue, and the overall atmosphere. The goal is a detailed reduction, not a brief summary. Provide only the condensed text. Chapter text:\n\n$text"
        return generateContent(prompt)
    }

    override suspend fun translate(text: String, targetLanguage: String): String {
        val prompt = "Translate the following novel chapter into $targetLanguage. Maintain the original atmosphere, tone, and formatting. Pay special attention to character names, place names, and unique terminology, ensuring they are translated consistently and correctly. If a name has a common translation, use it; otherwise, transliterate it appropriately. Provide only the translated text. Chapter text:\n\n$text"
        return generateContent(prompt)
    }

    private suspend fun generateContent(prompt: String): String {
        val selectedModel = model.ifBlank { "gemini-1.5-flash" }
        // Use v1 for GA models, v1beta for previews/experimental
        val apiVersion = if (selectedModel.contains("preview") || selectedModel.contains("beta")) "v1beta" else "v1"
        val url = "https://generativelanguage.googleapis.com/$apiVersion/models/$selectedModel:generateContent?key=$apiKey"
        
        val request = GeminiRequest(listOf(Content(listOf(Part(prompt)))))

        val response = app.post(
            url,
            json = request,
            timeout = 120
        )

        if (!response.isSuccessful) {
            // Fallback to v1beta if v1 fails and it's not already using it
            if (apiVersion == "v1") {
                val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent?key=$apiKey"
                val fallbackResponse = app.post(fallbackUrl, json = request, timeout = 120)
                if (fallbackResponse.isSuccessful) {
                    return parseResponse(fallbackResponse.text)
                }
            }
            throw Exception("Gemini API error: ${response.code} ${response.text}")
        }

        return parseResponse(response.text)
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
            val response = app.get("https://generativelanguage.googleapis.com/v1/models?key=$apiKey")
            if (response.isSuccessful) {
                val res = app.responseParser?.parse(response.text, ListModelsResponse::class)
                res?.models?.map { it.name.substringAfter("models/") } ?: emptyList()
            } else {
                listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash")
            }
        } catch (e: Exception) {
            logError(e)
            listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash")
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
}
