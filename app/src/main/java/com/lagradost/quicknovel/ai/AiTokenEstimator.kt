package com.lagradost.quicknovel.ai

import kotlin.math.ceil
import kotlin.math.max

data class AiTokenEstimate(
    val providerName: String,
    val modelName: String,
    val inputTokens: Int
)

object AiTokenEstimator {
    fun estimateOpenAiChat(modelName: String, messages: List<Pair<String, String>>): AiTokenEstimate {
        val tokens = messages.sumOf { (role, content) ->
            estimateTextTokens(role) + estimateTextTokens(content) + 4
        } + 3
        return AiTokenEstimate("OpenAI", modelName, tokens)
    }

    fun estimateGeminiContent(modelName: String, prompt: String): AiTokenEstimate {
        return AiTokenEstimate("Gemini", modelName, estimateTextTokens(prompt))
    }

    fun estimateClaudeMessage(modelName: String, prompt: String): AiTokenEstimate {
        return AiTokenEstimate("Claude", modelName, estimateTextTokens(prompt) + 8)
    }

    private fun estimateTextTokens(text: String): Int {
        var asciiRun = 0
        var tokens = 0

        fun flushAscii() {
            if (asciiRun > 0) {
                tokens += ceil(asciiRun / 4.0).toInt()
                asciiRun = 0
            }
        }

        for (char in text) {
            when {
                char.isCjkLike() -> {
                    flushAscii()
                    tokens += 1
                }
                char.isWhitespace() -> {
                    flushAscii()
                    tokens += 1
                }
                char.isLetterOrDigit() -> {
                    asciiRun += 1
                }
                else -> {
                    flushAscii()
                    tokens += 1
                }
            }
        }
        flushAscii()
        return max(tokens, 1)
    }

    private fun Char.isCjkLike(): Boolean {
        val code = code
        return code in 0x3400..0x9FFF ||
                code in 0xF900..0xFAFF ||
                code in 0x3040..0x30FF ||
                code in 0xAC00..0xD7AF
    }
}
