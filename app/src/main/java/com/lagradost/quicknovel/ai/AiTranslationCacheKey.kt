package com.lagradost.quicknovel.ai

object AiTranslationCacheKey {
    fun safeModelName(modelName: String): String {
        return modelName.ifBlank { "default" }.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    fun safeLanguage(targetLanguage: String): String {
        return targetLanguage.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    fun prefix(
        textHash: String,
        providerName: String,
        modelName: String,
        targetLanguage: String,
        novelId: String,
        promptSchemaVersion: Int = TranslationPromptBuilder.PROMPT_SCHEMA_VERSION
    ): String {
        return "ai_tra_v${promptSchemaVersion}_${textHash}_${providerName}_${safeModelName(modelName)}_${safeLanguage(targetLanguage)}_${novelId}_g"
    }

    fun legacy(
        textHash: String,
        providerName: String,
        modelName: String,
        targetLanguage: String
    ): String {
        return "ai_tra_${textHash}_${providerName}_${safeModelName(modelName)}_${safeLanguage(targetLanguage)}.txt"
    }

    fun build(
        textHash: String,
        providerName: String,
        modelName: String,
        targetLanguage: String,
        novelId: String,
        glossaryRevision: Long,
        glossaryHash: String,
        promptSchemaVersion: Int = TranslationPromptBuilder.PROMPT_SCHEMA_VERSION
    ): String {
        return "${prefix(textHash, providerName, modelName, targetLanguage, novelId, promptSchemaVersion)}${glossaryRevision}_${glossaryHash}.txt"
    }
}
