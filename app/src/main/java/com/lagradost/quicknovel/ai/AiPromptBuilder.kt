package com.lagradost.quicknovel.ai

object AiPromptBuilder {
    fun summarySystemMessage(): String {
        return "You are an expert editor that condenses novel chapters while preserving their essence, key dialogue, and plot progression."
    }

    fun summaryUserMessage(text: String): String {
        return "Condense the following novel chapter to be significantly shorter while retaining all key plot points, essential character dialogue, and the overall atmosphere. The goal is a detailed reduction, not a brief summary. Provide only the condensed text. Chapter text:\n\n$text"
    }
}
