package com.lagradost.quicknovel.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class AiCustomEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AiProviderClient
    private val key = "custom-key-not-for-default-provider"

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun config(model: String = "custom-model", apiKey: String = key, path: String = "/proxy/v1") =
        AiProviderConfig(apiKey, model, server.url(path).toString())

    private suspend fun failure(reason: AiFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (e: AiProviderException) {
            assertEquals(reason, e.failure)
            assertFalse(e.toString().contains(key))
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = AiProviderClient(endpoint = { error("Custom URLs must not contact a default provider") })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun allThreeProtocolsUseTheConfiguredGatewayPathAndTheirOwnAuthentication() = runBlocking {
        for (provider in AiProvider.entries) {
            val detail =
                if (provider == AiProvider.GEMINI)
                    """{"name":"models/custom-model","supportedGenerationMethods":["generateContent"]}"""
                else """{"id":"custom-model"}"""
            val array = if (provider == AiProvider.GEMINI) "models" else "data"
            server.enqueue(json("""{"$array":[$detail]}"""))
            server.enqueue(json(detail))
            assertEquals(AiModel("custom-model"), api.fetchModels(provider, config()).single())
            assertEquals("custom-model", api.testConnection(provider, config()).id)
            for (expected in listOf("/proxy/v1/models", "/proxy/v1/models/custom-model")) {
                val request = server.takeRequest()
                assertEquals(expected, request.requestUrl!!.encodedPath)
                assertEquals(
                    if (provider == AiProvider.OPENAI) "Bearer $key" else null,
                    request.getHeader("Authorization"),
                )
                assertEquals(if (provider == AiProvider.GEMINI) key else null, request.getHeader("x-goog-api-key"))
                assertEquals(if (provider == AiProvider.CLAUDE) key else null, request.getHeader("x-api-key"))
            }
        }
    }

    @Test
    fun deepseekStyleCatalogsCanVerifyASelectedModelWhenDetailRoutesAreUnsupported() = runBlocking {
        for (status in listOf(404, 405, 501)) {
            server.enqueue(MockResponse().setResponseCode(status))
            server.enqueue(
                json(
                    """{"object":"list","data":[{"id":"deepseek-custom","name":"DeepSeek model","owned_by":"deepseek"}]}"""
                )
            )
            assertEquals(
                AiModel("deepseek-custom", "DeepSeek model"),
                api.testConnection(AiProvider.OPENAI, config(model = "deepseek-custom", path = "/")),
            )
            assertEquals("/models/deepseek-custom", server.takeRequest().path)
            val list = server.takeRequest()
            assertEquals("/models", list.path)
            assertEquals("Bearer $key", list.getHeader("Authorization"))
        }
    }

    @Test
    fun catalogFallbackDoesNotVerifyAnUnknownModelOrHideAuthenticationAndServerErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json("""{"data":[{"id":"another-model"}]}"""))
        failure(AiFailure.NOT_FOUND) { api.testConnection(AiProvider.OPENAI, config()) }
        assertEquals(2, server.requestCount)
        for ((status, reason) in
            listOf(401 to AiFailure.AUTHENTICATION, 403 to AiFailure.FORBIDDEN, 503 to AiFailure.SERVER)) {
            val count = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status))
            failure(reason) { api.testConnection(AiProvider.OPENAI, config()) }
            assertEquals(count + 1, server.requestCount)
        }
    }

    @Test
    fun defaultProvidersStillReportMissingModelsWithoutTryingCompatibleCatalogFallback() = runBlocking {
        val defaultApi = AiProviderClient(endpoint = { server.url("/default/v1/") })
        server.enqueue(MockResponse().setResponseCode(404))
        failure(AiFailure.NOT_FOUND) { defaultApi.testConnection(AiProvider.OPENAI, AiProviderConfig(key, "missing")) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun explicitlyConfiguredSelfHostedServersCanBeUsedWithoutSendingDummyKeys() = runBlocking {
        for (provider in AiProvider.entries) {
            server.enqueue(
                json(
                    if (provider == AiProvider.GEMINI)
                        """{"name":"models/custom-model","supportedGenerationMethods":["generateContent"]}"""
                    else """{"id":"custom-model"}"""
                )
            )
            api.testConnection(provider, config(apiKey = ""))
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("x-goog-api-key"))
            assertNull(request.getHeader("x-api-key"))
        }
    }

    @Test
    fun malformedCustomUrlsAreRejectedBeforeAnyKeyCanBeSent() = runBlocking {
        for (url in
            listOf(
                "not-a-url",
                "https://user:password@example.com/v1",
                "https://example.com/v1?key=secret",
                "https://example.com/#x",
            )) {
            failure(AiFailure.INVALID_URL) { api.fetchModels(AiProvider.OPENAI, AiProviderConfig(key, "model", url)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun resettingTheUrlUsesTheDefaultResolverRatherThanThePreviousCustomHost() = runBlocking {
        val defaultApi = AiProviderClient(endpoint = { server.url("/official/v1/") })
        repeat(2) { server.enqueue(json("""{"id":"custom-model"}""")) }
        defaultApi.testConnection(AiProvider.OPENAI, config())
        defaultApi.testConnection(AiProvider.OPENAI, config().copy(baseUrl = ""))
        assertEquals("/proxy/v1/models/custom-model", server.takeRequest().path)
        assertEquals("/official/v1/models/custom-model", server.takeRequest().path)
    }

    @Test
    fun customEndpointRedirectsCannotSendCredentialsToAnotherHost() = runBlocking {
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            for (provider in AiProvider.entries) {
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/capture")))
                failure(AiFailure.INVALID_RESPONSE) { api.testConnection(provider, config()) }
            }
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }
}
