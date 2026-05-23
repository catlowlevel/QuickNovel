package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.AiSettings

interface AiProvider {
    suspend fun summarize(text: String): String
    suspend fun translate(text: String, targetLanguage: String): String
    suspend fun getModels(): List<String>
}
