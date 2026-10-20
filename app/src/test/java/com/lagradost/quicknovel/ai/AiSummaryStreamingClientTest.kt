package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.DataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiSummaryStreamingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AiProviderClient
    private val chapter = AiChapter("book", "chapter", "Novel", "Chapter", "Full original chapter.")
    private val config: AiProviderConfig
        get() = AiProviderConfig("fixture-key", "chosen-model", server.url("/custom/v1/").toString())

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = AiProviderClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun eachProtocolRequestsStreamingAtItsConfiguredEndpointAndReturnsOnlyTheFinishedSummary() = runBlocking {
        for (provider in AiProvider.entries) {
            server.enqueue(response(AiStreamFixtures.complete(provider)))
            val progress = mutableListOf<Long>()
            assertEquals(
                AiSummaryOutput(AiStreamFixtures.summary),
                api.streamSummary(provider, config, chapter, "en", progress::add),
            )
            assertEquals(90L, progress.last())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals(
                when (provider) {
                    AiProvider.OPENAI -> "/custom/v1/chat/completions"
                    AiProvider.GEMINI -> "/custom/v1/models/chosen-model:streamGenerateContent?alt=sse"
                    AiProvider.CLAUDE -> "/custom/v1/messages"
                },
                request.path,
            )
            assertEquals(
                if (provider == AiProvider.OPENAI) "Bearer fixture-key" else "fixture-key",
                request.getHeader(
                    when (provider) {
                        AiProvider.OPENAI -> "Authorization"
                        AiProvider.GEMINI -> "x-goog-api-key"
                        AiProvider.CLAUDE -> "x-api-key"
                    }
                ),
            )
            val body = DataStore.mapper.readTree(request.body.readUtf8())
            if (provider != AiProvider.GEMINI) assertTrue(body.path("stream").asBoolean())
            val source =
                if (provider == AiProvider.GEMINI) body.path("contents")[0].path("parts")[0].path("text").asText()
                else body.path("messages").last().path("content").asText()
            assertEquals(summarySource(chapter), source)
        }
    }

    @Test
    fun numericProgressArrivesBeforeTheBodyCompletesAndCancellingInterruptsTheStream() = runBlocking {
        val first = AiStreamFixtures.openText("An early sentence. ")
        val tail = AiStreamFixtures.complete(AiProvider.OPENAI)
        val release = CountDownLatch(1)
        val streaming = mockwebserver3.MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            // Explicitly flush one event, then hold the rest; throttling may buffer small responses.
            val body =
                object : MockResponseBody {
                    override val contentLength = (first + tail).toByteArray().size.toLong()

                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8(first).flush()
                        check(release.await(5, TimeUnit.SECONDS)) { "Streaming fixture was not released" }
                        sink.writeUtf8(tail)
                    }
                }
            streaming.enqueue(
                mockwebserver3.MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(body).build()
            )
            val firstProgress = CompletableDeferred<Long>()
            val call = async {
                api.streamSummary(
                    AiProvider.OPENAI,
                    config.copy(baseUrl = streaming.url("/v1/").toString()),
                    chapter,
                    "en",
                ) {
                    firstProgress.complete(it)
                }
            }
            assertTrue(withTimeout(2500) { firstProgress.await() } > 0)
            assertFalse(call.isCompleted)
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(1, streaming.requestCount)
        } finally {
            release.countDown()
            streaming.close()
        }
    }

    @Test
    fun utf8CharactersSplitAcrossSingleByteHttpChunksSurviveAllProviderFormats() = runBlocking {
        for (provider in AiProvider.entries) {
            val stream = AiStreamFixtures.complete(provider).replace("First sentence.", "你好😀")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(stream, 1))
            assertEquals("你好😀 Second sentence.", api.streamSummary(provider, config, chapter, "en") {}.text)
        }
    }

    @Test
    fun compatibleServersReturningJsonAreHandledWithoutRepeatingThePaidRequest() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"JSON recap"},"finish_reason":"stop"}]}""")
        )
        val progress = mutableListOf<Long>()
        assertEquals(
            AiSummaryOutput("JSON recap"),
            api.streamSummary(AiProvider.OPENAI, config, chapter, "en", progress::add),
        )
        assertTrue(progress.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 10_000)
    fun rejectedStreamingRequestsNeverRetryOrFollowCredentialRedirects() = runBlocking {
        for ((status, reason) in
            listOf(400 to AiFailure.REQUEST_REJECTED, 503 to AiFailure.SERVER, 307 to AiFailure.INVALID_RESPONSE)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(status)
                    .setHeader("Retry-After", "0")
                    .setHeader("Location", server.url("/must-not-follow"))
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"fixture-key"}}""")
            )
            val before = server.requestCount
            try {
                api.streamSummary(AiProvider.OPENAI, config, chapter, "en") {}
                fail("Expected failure")
            } catch (e: AiProviderException) {
                assertEquals(reason, e.failure)
                assertFalse(e.toString().contains("fixture-key"))
            }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun aDisconnectedBodyFailsInsteadOfReturningOrRetryingAPartialSummary() = runBlocking {
        server.enqueue(
            response(AiStreamFixtures.complete(AiProvider.OPENAI))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        try {
            api.streamSummary(AiProvider.OPENAI, config, chapter, "en") {}
            fail("Disconnected stream must fail")
        } catch (e: AiProviderException) {
            assertEquals(AiFailure.NETWORK, e.failure)
        }
        assertEquals(1, server.requestCount)
    }
}
