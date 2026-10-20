package com.lagradost.quicknovel.ai

internal object AiStreamFixtures {
    const val summary = "First sentence. Second sentence."

    fun event(json: String, name: String? = null): String = (name?.let { "event: $it\n" } ?: "") + "data: $json\n\n"

    fun openText(text: String): String = event("""{"choices":[{"index":0,"delta":{"content":"$text"}}]}""")

    fun complete(provider: AiProvider, limited: Boolean = false): String =
        when (provider) {
            AiProvider.OPENAI ->
                event("""{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""") +
                    event("""{"choices":[{"index":0,"delta":{"reasoning_content":"private reasoning"}}]}""") +
                    openText("First sentence. ") +
                    openText("Second sentence.") +
                    event(
                        """{"choices":[{"index":0,"delta":{},"finish_reason":"${if (limited) "length" else "stop"}"}]}"""
                    ) +
                    event(
                        """{"choices":[],"usage":{"completion_tokens":90,"prompt_tokens":999000,"total_tokens":999090}}"""
                    ) +
                    event("[DONE]")
            AiProvider.GEMINI ->
                event("""{"candidates":[{"content":{"parts":[{"text":"private reasoning","thought":true}]}}]}""") +
                    event("""{"candidates":[{"content":{"parts":[{"text":"First sentence. "}]}}]}""") +
                    event(
                        """{"candidates":[{"content":{"parts":[{"text":"Second sentence."}]},"finishReason":"${if (limited) "MAX_TOKENS" else "STOP"}"}],"usageMetadata":{"candidatesTokenCount":70,"thoughtsTokenCount":20,"totalTokenCount":999090}}"""
                    )
            AiProvider.CLAUDE ->
                event("""{"type":"message_start","message":{"usage":{"output_tokens":1}}}""") +
                    event(
                        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""
                    ) +
                    event(
                        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private reasoning"}}"""
                    ) +
                    event("""{"type":"content_block_stop","index":0}""") +
                    event("""{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""") +
                    event(
                        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"First sentence. "}}"""
                    ) +
                    event(
                        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Second sentence."}}"""
                    ) +
                    event("""{"type":"content_block_stop","index":1}""") +
                    event(
                        """{"type":"message_delta","delta":{"stop_reason":"${if (limited) "max_tokens" else "end_turn"}"},"usage":{"output_tokens":90}}"""
                    ) +
                    event("""{"type":"message_stop"}""")
        }
}
