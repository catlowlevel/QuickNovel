package com.lagradost.quicknovel.ai

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AiEventStreamParserTest {
    private fun parse(provider: AiProvider, stream: String, progress: (Long) -> Unit = {}): AiSummaryOutput =
        AiEventStreamParser(provider, progress).read(Buffer().writeUtf8(stream))

    private fun failure(provider: AiProvider, stream: String, expected: AiFailure = AiFailure.INVALID_RESPONSE) {
        try {
            parse(provider, stream)
            fail("An unsuccessful stream must not yield summary output")
        } catch (e: AiProviderException) {
            assertEquals(expected, e.failure)
            assertFalse(e.toString().contains("private reasoning"))
            assertFalse(e.toString().contains("secret-key"))
            assertNull(e.cause)
        }
    }

    @Test
    fun allProtocolsJoinVisibleDeltasAndReportOnlyMonotonicOutputCounts() {
        for (provider in AiProvider.entries) {
            val progress = mutableListOf<Long>()
            val output = parse(provider, AiStreamFixtures.complete(provider), progress::add)
            assertEquals(AiSummaryOutput(AiStreamFixtures.summary), output)
            assertFalse(output.text.contains("private reasoning"))
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
            assertTrue(progress.first() in 1..10) // No input-token count or prompt-estimate overhead.
            assertEquals(90L, progress.last()) // Cumulative usage, including thinking, excludes prompt tokens.
        }
    }

    @Test
    fun validTokenLimitFinishesPreserveTheIncompleteFlagForEveryProvider() {
        for (provider in AiProvider.entries) {
            assertEquals(AiSummaryOutput(AiStreamFixtures.summary, truncated = true),
                parse(provider, AiStreamFixtures.complete(provider, limited = true)))
        }
    }

    @Test
    fun framingSupportsBomCommentsMultilineDataAndAllSseLineEndings() {
        val first = "\uFEFF: heartbeat\nid: ignored\nevent: message\ndata: {\"choices\":[\ndata: {\"index\":0,\"delta\":{\"content\":\"你好😀\"}}]}\n\n"
        val finish = AiStreamFixtures.event("""{"choices":[{"finish_reason":"stop","delta":{}}]}""") +
            AiStreamFixtures.event("[DONE]")
        for (ending in listOf("\n", "\r\n", "\r")) {
            assertEquals("你好😀", parse(AiProvider.OPENAI, (first + finish).replace("\n", ending)).text)
        }
    }

    @Test
    fun prematureEofAndMissingTerminalMarkersNeverReturnPartialOutput() {
        failure(AiProvider.OPENAI, AiStreamFixtures.openText("A partial summary"))
        failure(AiProvider.OPENAI, AiStreamFixtures.complete(AiProvider.OPENAI).substringBefore("data: [DONE]"))
        failure(AiProvider.GEMINI, AiStreamFixtures.event("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""))
        failure(AiProvider.CLAUDE, AiStreamFixtures.complete(AiProvider.CLAUDE).substringBefore("data: {\"type\":\"message_stop\"}"))
        failure(AiProvider.OPENAI, AiStreamFixtures.event("[DONE]"))
        failure(AiProvider.CLAUDE, AiStreamFixtures.event("""{"type":"message_stop"}"""))
    }

    @Test
    fun inBandErrorsAfterTextAreTypedSanitizedAndNeverAcceptedAsCompletedOutput() {
        val cases = listOf(
            Triple(AiProvider.OPENAI, """{"error":{"code":"rate_limit_error","message":"secret-key"}}""", AiFailure.RATE_LIMIT),
            Triple(AiProvider.GEMINI, """{"error":{"code":401,"status":"UNAUTHENTICATED","message":"secret-key"}}""", AiFailure.AUTHENTICATION),
            Triple(AiProvider.CLAUDE, """{"type":"error","error":{"type":"overloaded_error","message":"secret-key"}}""", AiFailure.SERVER),
        )
        for ((provider, error, expected) in cases) {
            val prefix = AiStreamFixtures.complete(provider).substringBeforeLast("data:")
            failure(provider, prefix + AiStreamFixtures.event(error, "error"), expected)
        }
    }

    @Test
    fun providerRefusalsAndSafetyBlocksRejectAlreadyReceivedText() {
        failure(AiProvider.OPENAI, AiStreamFixtures.openText("partial") + AiStreamFixtures.event(
            """{"choices":[{"delta":{"refusal":"Blocked"}}]}"""), AiFailure.RESPONSE_BLOCKED)
        failure(AiProvider.OPENAI, AiStreamFixtures.openText("partial") + AiStreamFixtures.event(
            """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""), AiFailure.RESPONSE_BLOCKED)
        failure(AiProvider.GEMINI, AiStreamFixtures.event("""{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}""") +
            AiStreamFixtures.event("""{"promptFeedback":{"blockReason":"SAFETY"}}"""), AiFailure.RESPONSE_BLOCKED)
        failure(AiProvider.CLAUDE, AiStreamFixtures.complete(AiProvider.CLAUDE).replace("\"end_turn\"", "\"refusal\""), AiFailure.RESPONSE_BLOCKED)
    }

    @Test
    fun malformedOversizeAndUnsupportedFinishEventsCannotBeUsedAsSummaries() {
        failure(AiProvider.OPENAI, AiStreamFixtures.event("{not valid JSON"))
        failure(AiProvider.OPENAI, "data: " + "x".repeat(256_001))
        val largeDelta = AiStreamFixtures.openText("x".repeat(200_000))
        failure(AiProvider.OPENAI, largeDelta.repeat(11))
        failure(AiProvider.OPENAI, AiStreamFixtures.complete(AiProvider.OPENAI).replace("\"stop\"", "\"tool_calls\""))
        failure(AiProvider.CLAUDE, AiStreamFixtures.complete(AiProvider.CLAUDE).replace("\"end_turn\"", "\"tool_use\""))
        failure(AiProvider.CLAUDE, AiStreamFixtures.event("""{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"orphan"}}"""))
    }

    @Test
    fun completedButEmptyOrThinkingOnlyStreamsAreEmptyResponses() {
        failure(AiProvider.OPENAI, AiStreamFixtures.event("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""") +
            AiStreamFixtures.event("[DONE]"), AiFailure.EMPTY_RESPONSE)
        failure(AiProvider.GEMINI, AiStreamFixtures.event(
            """{"candidates":[{"content":{"parts":[{"text":"private reasoning","thought":true}]},"finishReason":"STOP"}]}"""), AiFailure.EMPTY_RESPONSE)
        failure(AiProvider.CLAUDE, AiStreamFixtures.event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""") +
            AiStreamFixtures.event("""{"type":"message_stop"}"""), AiFailure.EMPTY_RESPONSE)
    }

    @Test
    fun unrelatedChoicesAndNewClaudeEventTypesDoNotPolluteTheSummary() {
        val extra = AiStreamFixtures.event("""{"choices":[{"index":1,"delta":{"content":"Wrong answer","refusal":"Ignored other choice"}}]}""")
        assertEquals(AiStreamFixtures.summary, parse(AiProvider.OPENAI, extra + AiStreamFixtures.complete(AiProvider.OPENAI)).text)
        val ping = AiStreamFixtures.event("""{"type":"ping"}""", "ping") +
            AiStreamFixtures.event("""{"type":"future_event","text":"Not a text delta"}""")
        assertEquals(AiStreamFixtures.summary, parse(AiProvider.CLAUDE, ping + AiStreamFixtures.complete(AiProvider.CLAUDE)).text)
    }

    @Test
    fun outputEstimatesDoNotDependOnChunkSizeOrUnicodeSurrogateBoundaries() {
        val text = "Hello 你好 😀"
        val whole = mutableListOf<Long>()
        AiStreamTokenCounter(whole::add).append(text)
        val split = mutableListOf<Long>()
        val counter = AiStreamTokenCounter(split::add)
        text.forEach { counter.append(it.toString()) }
        assertEquals(whole.last(), split.last())
        assertEquals(estimateSummaryTokens(text) - 32, whole.last())
        counter.report(100)
        counter.report(50)
        counter.append("a")
        assertEquals(100L, split.last())
    }
}
