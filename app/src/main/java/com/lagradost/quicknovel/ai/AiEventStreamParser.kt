package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.DataStore
import okio.Buffer
import okio.BufferedSource

/** Consumes provider events privately; only numeric progress escapes before a successful finish. */
internal class AiEventStreamParser(
    private val provider: AiProvider,
    onProgress: (Long) -> Unit,
) {
    private val output = StringBuilder()
    private val tokens = AiStreamTokenCounter(onProgress)
    private val blocks = mutableMapOf<Int, String>()
    private var finished = false
    private var reason: String? = null
    private var truncated = false

    fun read(source: BufferedSource): AiSummaryOutput {
        val line = Buffer()
        val data = StringBuilder()
        var event = ""
        var firstLine = true
        var afterCarriageReturn = false
        var wireBytes = 0L

        fun dispatch() {
            if (data.isNotEmpty()) accept(event, data.toString().removeSuffix("\n"))
            data.setLength(0)
            event = ""
        }
        fun readLine() {
            val value = line.readUtf8().let { if (firstLine) it.removePrefix("\uFEFF") else it }
            firstLine = false
            if (value.isEmpty()) {
                dispatch()
            } else if (!value.startsWith(':')) {
                val field = value.substringBefore(':')
                val content = value.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "event" -> event = content
                    "data" -> {
                        if (data.length + content.length > 1_000_000) invalid()
                        data.append(content).append('\n')
                    }
                }
            }
        }
        // Bounded buffered bytes also handle CR-only framing and UTF-8 split across network packets.
        while (!finished && !source.exhausted()) {
            if (++wireBytes > 16_000_000) invalid()
            val byte = source.readByte().toInt() and 0xff
            if (afterCarriageReturn && byte == 10) {
                afterCarriageReturn = false
                continue
            }
            afterCarriageReturn = byte == 13
            if (byte == 10 || byte == 13) readLine() else {
                if (line.size >= 256_000) invalid()
                line.writeByte(byte)
            }
        }
        if (!finished) {
            if (line.size > 0) readLine()
            dispatch()
        }
        if (!finished || reason == null) invalid()
        if (output.isBlank()) throw AiProviderException(AiFailure.EMPTY_RESPONSE)
        return AiSummaryOutput(output.toString().trim(), truncated)
    }

    private fun accept(event: String, data: String) {
        if (provider == AiProvider.OPENAI && data == "[DONE]") {
            finished = true
            return
        }
        val root = try {
            DataStore.mapper.readTree(data)?.takeIf { it.isObject } ?: invalid()
        } catch (_: JsonProcessingException) {
            invalid()
        }
        if (event == "error" || root.path("type").asText() == "error" || root.hasNonNull("error")) {
            throw AiProviderException(streamFailure(root.path("error")))
        }
        when (provider) {
            AiProvider.OPENAI -> openAi(root)
            AiProvider.GEMINI -> gemini(root)
            AiProvider.CLAUDE -> claude(root, event)
        }
    }

    private fun append(text: String, visible: Boolean = true) {
        tokens.append(text)
        if (visible) {
            if (output.length + text.length > 2_000_000) invalid()
            output.append(text)
        }
    }

    private fun finish(value: String, accepted: Set<String>, limited: String) {
        if (value !in accepted) invalid()
        reason = value
        truncated = value == limited
    }

    private fun openAi(root: JsonNode) {
        tokens.report(root.path("usage").tokenCount("completion_tokens"))
        val choice = root.path("choices").firstOrNull { it.path("index").asInt(0) == 0 } ?: return
        val delta = choice.path("delta")
        if (!delta.path("refusal").asText("").isNullOrBlank() || choice.path("finish_reason").asText() == "content_filter") {
            throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
        }
        delta.get("reasoning_content")?.takeIf { it.isTextual }?.asText()?.let { append(it, visible = false) }
        delta.get("content")?.takeIf { it.isTextual }?.asText()?.let { append(it) }
        choice.get("finish_reason")?.takeIf { it.isTextual }?.asText()?.let {
            finish(it, setOf("stop", "length"), "length")
        }
    }

    private fun gemini(root: JsonNode) {
        if (root.path("promptFeedback").hasNonNull("blockReason")) throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
        val usage = root.path("usageMetadata")
        tokens.report(usage.tokenCount("candidatesTokenCount") + usage.tokenCount("thoughtsTokenCount"))
        val candidate = root.path("candidates").firstOrNull { it.path("index").asInt(0) == 0 } ?: return
        candidate.path("content").path("parts").forEach { part ->
            part.get("text")?.takeIf { it.isTextual }?.asText()?.let { append(it, visible = !part.path("thought").asBoolean(false)) }
        }
        candidate.get("finishReason")?.takeIf { it.isTextual }?.asText()?.let {
            if (it !in setOf("STOP", "MAX_TOKENS")) throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
            finish(it, setOf("STOP", "MAX_TOKENS"), "MAX_TOKENS")
            finished = true
        }
    }

    private fun claude(root: JsonNode, event: String) {
        when (root.path("type").asText(event)) {
            "message_start" -> tokens.report(root.path("message").path("usage").tokenCount("output_tokens"))
            "content_block_start" -> {
                val index = root.path("index").asInt(-1)
                if (index < 0 || blocks.containsKey(index)) invalid()
                val block = root.path("content_block")
                val type = block.path("type").asText()
                blocks[index] = type
                if (type == "text") {
                    if (output.isNotEmpty()) output.append('\n')
                    append(block.path("text").asText(""))
                } else if (type == "thinking") append(block.path("thinking").asText(""), visible = false)
            }
            "content_block_delta" -> {
                val type = blocks[root.path("index").asInt(-1)] ?: invalid()
                val delta = root.path("delta")
                when (delta.path("type").asText()) {
                    "text_delta" -> {
                        if (type != "text") invalid()
                        append(delta.path("text").asText(""))
                    }
                    "thinking_delta" -> {
                        if (type != "thinking") invalid()
                        append(delta.path("thinking").asText(""), visible = false)
                    }
                }
            }
            "content_block_stop" -> blocks.remove(root.path("index").asInt(-1))
            "message_delta" -> {
                tokens.report(root.path("usage").tokenCount("output_tokens"))
                root.path("delta").get("stop_reason")?.takeIf { it.isTextual }?.asText()?.let {
                    if (it == "refusal") throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
                    finish(it, setOf("end_turn", "stop_sequence", "max_tokens"), "max_tokens")
                }
            }
            "message_stop" -> finished = true
            // Ping and future event types carry no visible summary text.
        }
    }

    private fun JsonNode.tokenCount(field: String): Long = path(field).takeIf {
        it.isIntegralNumber && it.canConvertToLong()
    }?.asLong()?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: 0L

    private fun streamFailure(error: JsonNode): AiFailure {
        val codes = listOf(error.path("type").asText(), error.path("status").asText(), error.path("code").asText())
        return when {
            codes.any { it in setOf("401", "authentication_error", "UNAUTHENTICATED", "invalid_api_key") } -> AiFailure.AUTHENTICATION
            codes.any { it in setOf("403", "permission_error", "PERMISSION_DENIED") } -> AiFailure.FORBIDDEN
            codes.any { it in setOf("429", "rate_limit_error", "RESOURCE_EXHAUSTED") } -> AiFailure.RATE_LIMIT
            codes.any { it in setOf("408", "DEADLINE_EXCEEDED") } -> AiFailure.TIMEOUT
            codes.any { it in setOf("400", "invalid_request_error", "INVALID_ARGUMENT") } -> AiFailure.REQUEST_REJECTED
            else -> AiFailure.SERVER
        }
    }

    private fun invalid(): Nothing = throw AiProviderException(AiFailure.INVALID_RESPONSE)
}

/** A chunk-independent approximation with no prompt overhead; reasoning contributes only a count. */
internal class AiStreamTokenCounter(private val onProgress: (Long) -> Unit) {
    private var quarters = 0L
    private var count = 0L

    fun append(text: String) {
        // A supplementary code point occupies two non-ASCII UTF-16 characters (eight quarters).
        quarters += text.sumOf { if (it.code < 128) 1L else 4L }
        report((quarters + 3) / 4)
    }

    fun report(value: Long) {
        if (value > count) {
            count = value
            onProgress(count)
        }
    }
}
