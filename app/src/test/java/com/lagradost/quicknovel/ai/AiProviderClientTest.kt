package com.lagradost.quicknovel.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AiProviderClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AiProviderClient
    private val key = "test-api-secret-do-not-display"

    private fun config(model: String = "model-a") = AiProviderConfig(key, model)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = AiProviderClient(endpoint = { server.url("/${it.name.lowercase()}/") })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun failure(expected: AiFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: AiProviderException) {
            assertEquals(expected, e.failure)
            assertFalse(e.toString().contains(key))
            assertNull(e.cause)
        }
    }

    @Test
    fun eachProviderUsesItsOwnHeadersAndRetrievesTheSelectedModelWithoutGeneration() = runBlocking {
        for (provider in AiProvider.entries) {
            server.enqueue(
                json(
                    if (provider == AiProvider.GEMINI)
                        """{"name":"models/model-a","displayName":"Model A","supportedGenerationMethods":["generateContent"]}"""
                    else """{"id":"model-a","display_name":"Model A"}"""
                )
            )
            assertEquals("model-a", api.testConnection(provider, config()).id)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/${provider.name.lowercase()}/models/model-a", request.path)
            assertEquals(0L, request.bodySize)
            assertNull(request.getHeader("Cookie"))
            assertEquals(if (provider == AiProvider.OPENAI) "Bearer $key" else null, request.getHeader("Authorization"))
            assertEquals(if (provider == AiProvider.GEMINI) key else null, request.getHeader("x-goog-api-key"))
            assertEquals(if (provider == AiProvider.CLAUDE) key else null, request.getHeader("x-api-key"))
            assertEquals(
                if (provider == AiProvider.CLAUDE) "2023-06-01" else null,
                request.getHeader("anthropic-version"),
            )
            assertFalse(request.path!!.contains(key))
        }
    }

    @Test
    fun geminiPaginationUsesOpaqueTokensFiltersNonGeneratingModelsAndDeduplicatesIds() = runBlocking {
        server.enqueue(
            json(
                """{"models":[
            {"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],
            "nextPageToken":"a+/=&"}"""
            )
        )
        server.enqueue(
            json(
                """{"models":[
            {"name":"models/gemini-a","displayName":"Gemini A","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}"""
            )
        )
        assertEquals(listOf("gemini-a", "gemini-b"), api.fetchModels(AiProvider.GEMINI, config()).map { it.id })
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("1000", first.requestUrl!!.queryParameter("pageSize"))
        assertEquals("a+/=&", second.requestUrl!!.queryParameter("pageToken"))
        assertNull(second.requestUrl!!.queryParameter("key"))
        assertEquals(key, second.getHeader("x-goog-api-key"))
    }

    @Test
    fun claudeFetchesAllPagesAndUsesDisplayNames() = runBlocking {
        server.enqueue(
            json("""{"data":[{"id":"claude-b","display_name":"Claude B"}],"has_more":true,"last_id":"claude-b"}""")
        )
        server.enqueue(json("""{"data":[{"id":"claude-a","display_name":"Claude A"}],"has_more":false}"""))
        assertEquals(
            listOf(AiModel("claude-a", "Claude A"), AiModel("claude-b", "Claude B")),
            api.fetchModels(AiProvider.CLAUDE, config()),
        )
        server.takeRequest()
        assertEquals("claude-b", server.takeRequest().requestUrl!!.queryParameter("after_id"))
    }

    @Test
    fun openaiModelIdsRemainUsableWithoutDisplayNamesAndEmptyCatalogsAreValid() = runBlocking {
        server.enqueue(json("""{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"}]}"""))
        assertEquals(listOf(AiModel("model-a"), AiModel("model-b")), api.fetchModels(AiProvider.OPENAI, config()))
        server.enqueue(json("""{"data":[]}"""))
        assertTrue(api.fetchModels(AiProvider.OPENAI, config()).isEmpty())
    }

    @Test
    fun providerAliasesAreAcceptedAndGeminiResourcePrefixesAreNormalized() = runBlocking {
        server.enqueue(json("""{"id":"resolved-version","display_name":"Resolved Model"}"""))
        assertEquals("resolved-version", api.testConnection(AiProvider.CLAUDE, config("model-alias")).id)
        server.takeRequest()
        server.enqueue(json("""{"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]}"""))
        api.testConnection(AiProvider.GEMINI, config(" models/gemini-test "))
        assertEquals("/gemini/models/gemini-test", server.takeRequest().path)
    }

    @Test
    fun errorsAreActionableWithoutEchoingProviderBodiesOrCredentials() = runBlocking {
        for ((code, reason) in
            listOf(
                401 to AiFailure.AUTHENTICATION,
                403 to AiFailure.FORBIDDEN,
                404 to AiFailure.NOT_FOUND,
                429 to AiFailure.RATE_LIMIT,
                503 to AiFailure.SERVER,
            )) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("Key $key was rejected"))
            failure(reason) { api.testConnection(AiProvider.OPENAI, config()) }
        }
    }

    @Test
    fun geminiInvalidAndExpiredKeyDetailsAreReportedWithoutEchoingSecretMessages() = runBlocking {
        for (reason in listOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_NOT_FOUND")) {
            server.enqueue(
                json("""{"error":{"message":"Rejected $key","details":[{"reason":"$reason"}]}}""").setResponseCode(400)
            )
            failure(AiFailure.AUTHENTICATION) { api.fetchModels(AiProvider.GEMINI, config()) }
        }
        server.enqueue(json("""{"error":{"details":[{"reason":"INVALID_PARAMETER"}]}}""").setResponseCode(400))
        failure(AiFailure.INVALID_RESPONSE) { api.fetchModels(AiProvider.GEMINI, config()) }
    }

    @Test
    fun malformedOrUnrelatedSuccessResponsesCannotReportAWorkingConnection() = runBlocking {
        for (body in listOf("<html>Sign in</html>", "[]", "{}", """{"data":[]}""")) {
            server.enqueue(json(body))
            failure(AiFailure.INVALID_RESPONSE) { api.testConnection(AiProvider.OPENAI, config()) }
        }
        server.enqueue(json("""{"data":{}}"""))
        failure(AiFailure.INVALID_RESPONSE) { api.fetchModels(AiProvider.OPENAI, config()) }
        server.enqueue(json("""{"name":"models/embed","supportedGenerationMethods":["embedContent"]}"""))
        failure(AiFailure.UNSUPPORTED_MODEL) { api.testConnection(AiProvider.GEMINI, config("embed")) }
    }

    @Test
    fun repeatedOrMissingPaginationCursorsFailInsteadOfLoopingOrReturningPartialResults() = runBlocking {
        repeat(2) { server.enqueue(json("""{"models":[],"nextPageToken":"same"}""")) }
        failure(AiFailure.INVALID_RESPONSE) { api.fetchModels(AiProvider.GEMINI, config()) }
        assertEquals(2, server.requestCount)
        server.enqueue(json("""{"data":[],"has_more":true}"""))
        failure(AiFailure.INVALID_RESPONSE) { api.fetchModels(AiProvider.CLAUDE, config()) }
    }

    @Test
    fun redirectsCannotForwardProviderCredentialsToAnotherServer() = runBlocking {
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            other.enqueue(json("""{"id":"model-a"}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
            failure(AiFailure.INVALID_RESPONSE) { api.testConnection(AiProvider.GEMINI, config()) }
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun keysAndModelIdsAreValidatedBeforeSendingAndIdsCannotInjectUrlParameters() = runBlocking {
        failure(AiFailure.MISSING_KEY) { api.fetchModels(AiProvider.OPENAI, AiProviderConfig()) }
        failure(AiFailure.INVALID_KEY) { api.fetchModels(AiProvider.OPENAI, AiProviderConfig("bad\nheader")) }
        failure(AiFailure.MISSING_MODEL) { api.testConnection(AiProvider.OPENAI, config("")) }
        for (model in listOf(".", "..", "has space", "line\nbreak")) {
            failure(AiFailure.INVALID_MODEL) { api.testConnection(AiProvider.OPENAI, config(model)) }
        }
        assertEquals(0, server.requestCount)
        server.enqueue(json("""{"id":"custom-model"}"""))
        api.testConnection(AiProvider.OPENAI, config("ft:org/model?query#fragment"))
        val request = server.takeRequest()
        assertNull(request.requestUrl!!.query)
        assertNull(request.requestUrl!!.fragment)
        assertTrue(request.requestUrl!!.encodedPath.contains("%2F"))
    }

    @Test
    fun slowBodiesTimeOutAndCancelledRequestsReturnPromptly() = runBlocking {
        val shortClient = OkHttpClient.Builder().followRedirects(false).callTimeout(200, TimeUnit.MILLISECONDS).build()
        val shortApi = AiProviderClient(shortClient, endpoint = { server.url("/") })
        server.enqueue(json("""{"id":"model-a"}""").setBodyDelay(2, TimeUnit.SECONDS))
        failure(AiFailure.TIMEOUT) { shortApi.testConnection(AiProvider.OPENAI, config()) }
        server.takeRequest()
        server.enqueue(json("""{"id":"model-a"}""").setBodyDelay(2, TimeUnit.SECONDS))
        val request = async { api.testConnection(AiProvider.OPENAI, config()) }
        // The async request must start before waiting for its HTTP headers.
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }
}
