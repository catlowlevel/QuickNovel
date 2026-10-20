package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.DataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AiSummaryClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AiProviderClient
    private val key = "fixture-summary-key"
    private val chapter = AiChapter("book", "chapter-7", "Novel", "Chapter 7", "Original chapter: \"quoted\".\n你好世界.")

    private fun config(model: String = "selected-model") =
        AiProviderConfig(key, model, server.url("/proxy/v1").toString())

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun response(provider: AiProvider, finish: Boolean = false): String =
        when (provider) {
            AiProvider.OPENAI ->
                """{"choices":[{"finish_reason":"${if (finish) "length" else "stop"}","message":{"content":"Summary text","reasoning_content":"PRIVATE REASONING"}}]}"""
            AiProvider.GEMINI ->
                """{"candidates":[{"finishReason":"${if (finish) "MAX_TOKENS" else "STOP"}","content":{"parts":[{"text":"PRIVATE REASONING","thought":true},{"text":"Summary text"}]}}]}"""
            AiProvider.CLAUDE ->
                """{"stop_reason":"${if (finish) "max_tokens" else "end_turn"}","content":[{"type":"thinking","thinking":"PRIVATE REASONING"},{"type":"text","text":"Summary text"}]}"""
        }

    private suspend fun failure(expected: AiFailure, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $expected")
        } catch (e: AiProviderException) {
            assertEquals(expected, e.failure)
            assertFalse(e.toString().contains(key))
            assertFalse(e.toString().contains(chapter.text))
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = AiProviderClient(endpoint = { server.url("/default/") })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun allProtocolsPostOneOriginalChapterUsingTheirConfiguredModelPathAndAuthentication() = runBlocking {
        for (provider in AiProvider.entries) {
            server.enqueue(json(response(provider)))
            assertEquals(AiSummaryOutput("Summary text"), api.summarize(provider, config(), chapter, "en-US"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertNull(request.requestUrl!!.query)
            assertEquals(
                when (provider) {
                    AiProvider.OPENAI -> "/proxy/v1/chat/completions"
                    AiProvider.GEMINI -> "/proxy/v1/models/selected-model:generateContent"
                    AiProvider.CLAUDE -> "/proxy/v1/messages"
                },
                request.path,
            )
            assertEquals(
                key,
                request
                    .getHeader(
                        when (provider) {
                            AiProvider.OPENAI -> "Authorization"
                            AiProvider.GEMINI -> "x-goog-api-key"
                            AiProvider.CLAUDE -> "x-api-key"
                        }
                    )
                    ?.removePrefix("Bearer "),
            )
            val body = DataStore.mapper.readTree(request.body.readUtf8())
            val source =
                if (provider == AiProvider.GEMINI) body.path("contents")[0].path("parts")[0].path("text").asText()
                else body.path("messages").last().path("content").asText()
            assertEquals(summarySource(chapter), source)
            if (provider != AiProvider.GEMINI) assertEquals("selected-model", body.path("model").asText())
            if (provider == AiProvider.CLAUDE) assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            if (provider == AiProvider.OPENAI) { // Compatible endpoints, including DeepSeek, use max_tokens.
                assertEquals(8192, body.path("max_tokens").asInt())
                assertFalse(body.has("max_completion_tokens"))
            }
        }
    }

    @Test
    fun officialOpenAiUsesTheModernCompletionLimitAndDisablesStoredCompletions() = runBlocking {
        val transport = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val official =
            AiProviderClient(transport, endpoint = { server.url("/v1/").newBuilder().host("api.openai.com").build() })
        server.enqueue(json(response(AiProvider.OPENAI)))
        official.summarize(AiProvider.OPENAI, AiProviderConfig(key, "selected-model"), chapter, "en")
        val body = DataStore.mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals(8192, body.path("max_completion_tokens").asInt())
        assertFalse(body.path("store").asBoolean(true))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun partialSummariesRemainReadableAndThoughtBlocksNeverBecomeSummaryText() = runBlocking {
        for (provider in AiProvider.entries) {
            server.enqueue(json(response(provider, finish = true)))
            assertEquals(
                AiSummaryOutput("Summary text", truncated = true),
                api.summarize(provider, config(), chapter, "id"),
            )
        }
    }

    @Test
    fun refusalsEmptyOutputAndUnexpectedShapesAreNotShownAsSuccessfulSummaries() = runBlocking {
        val cases =
            listOf(
                Triple(
                    AiProvider.OPENAI,
                    """{"choices":[{"finish_reason":"content_filter","message":{}}]}""",
                    AiFailure.RESPONSE_BLOCKED,
                ),
                Triple(
                    AiProvider.OPENAI,
                    """{"choices":[{"finish_reason":"stop","message":{"refusal":"No","content":"not a summary"}}]}""",
                    AiFailure.RESPONSE_BLOCKED,
                ),
                Triple(
                    AiProvider.GEMINI,
                    """{"promptFeedback":{"blockReason":"SAFETY"}}""",
                    AiFailure.RESPONSE_BLOCKED,
                ),
                Triple(AiProvider.GEMINI, """{"candidates":[{"finishReason":"SAFETY"}]}""", AiFailure.RESPONSE_BLOCKED),
                Triple(AiProvider.CLAUDE, """{"stop_reason":"refusal","content":[]}""", AiFailure.RESPONSE_BLOCKED),
                Triple(AiProvider.CLAUDE, """{"stop_reason":"tool_use","content":[]}""", AiFailure.INVALID_RESPONSE),
                Triple(
                    AiProvider.OPENAI,
                    """{"choices":[{"finish_reason":"length","message":{"content":null}}]}""",
                    AiFailure.EMPTY_RESPONSE,
                ),
                Triple(
                    AiProvider.GEMINI,
                    """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"thought","thought":true}]}}]}""",
                    AiFailure.EMPTY_RESPONSE,
                ),
                Triple(AiProvider.CLAUDE, """{"stop_reason":"end_turn","content":[]}""", AiFailure.EMPTY_RESPONSE),
                Triple(AiProvider.OPENAI, "{}", AiFailure.INVALID_RESPONSE),
                Triple(AiProvider.OPENAI, "<html>Login</html>", AiFailure.INVALID_RESPONSE),
            )
        for ((provider, body, reason) in cases) {
            server.enqueue(json(body))
            failure(reason) { api.summarize(provider, config(), chapter, "en") }
        }
    }

    @Test(timeout = 10_000)
    fun providerFailuresAreCredentialFreeAndNoAutomaticPaidRetryOccurs() = runBlocking {
        val cases =
            listOf(
                400 to AiFailure.REQUEST_REJECTED,
                401 to AiFailure.AUTHENTICATION,
                403 to AiFailure.FORBIDDEN,
                408 to AiFailure.TIMEOUT,
                413 to AiFailure.REQUEST_TOO_LARGE,
                429 to AiFailure.RATE_LIMIT,
                503 to AiFailure.SERVER,
            )
        for ((code, reason) in cases) {
            val before = server.requestCount
            server.enqueue(
                json("""{"error":{"message":"secret $key"}}""").setResponseCode(code).setHeader("Retry-After", "0")
            )
            failure(reason) { api.summarize(AiProvider.OPENAI, config(), chapter, "en") }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun redirectsDoNotSendChaptersOrKeysToAnotherServer() = runBlocking {
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/stolen")))
            failure(AiFailure.INVALID_RESPONSE) { api.summarize(AiProvider.OPENAI, config(), chapter, "en") }
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun cancellationInterruptsASlowGenerationBody() = runBlocking {
        server.enqueue(json(response(AiProvider.OPENAI)).setBodyDelay(3, TimeUnit.SECONDS))
        val call = async { api.summarize(AiProvider.OPENAI, config(), chapter, "en") }
        yield()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
    }

    @Test
    fun invalidOrOversizeInputIsNeverTruncatedAndSentWithoutConsent() = runBlocking {
        failure(AiFailure.EMPTY_CHAPTER) {
            api.summarize(AiProvider.OPENAI, config(), chapter.copy(text = " \n"), "en")
        }
        failure(AiFailure.REQUEST_TOO_LARGE) {
            api.summarize(AiProvider.OPENAI, config(), chapter.copy(text = "界".repeat(400_000)), "en")
        }
        failure(AiFailure.MISSING_KEY) {
            api.summarize(AiProvider.OPENAI, AiProviderConfig(model = "model"), chapter, "en")
        }
        failure(AiFailure.MISSING_MODEL) { api.summarize(AiProvider.OPENAI, config(""), chapter, "en") }
        failure(AiFailure.INVALID_URL) {
            api.summarize(AiProvider.OPENAI, config().copy(baseUrl = "https://user:secret@example.com"), chapter, "en")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keylessLocalServicesAndDefaultVersionPrefixesArePreserved() = runBlocking {
        server.enqueue(json(response(AiProvider.GEMINI)))
        api.summarize(AiProvider.GEMINI, config("models/gemini-test").copy(apiKey = ""), chapter, "en")
        assertEquals(
            "/proxy/v1/models/gemini-test:generateContent",
            server.takeRequest().let {
                assertNull(it.getHeader("x-goog-api-key"))
                it.path
            },
        )
        server.enqueue(json(response(AiProvider.CLAUDE)))
        api.summarize(AiProvider.CLAUDE, AiProviderConfig(key, "selected-model"), chapter, "en")
        assertEquals("/default/messages", server.takeRequest().path)
    }
}
