package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.DataStore
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate from provider scraping: normal TLS, no shared cookies, and no credential redirects. */
class AiProviderClient internal constructor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val endpoint: (AiProvider) -> HttpUrl = { it.apiUrl.toHttpUrl() },
) : AiProviderApi {
    override suspend fun fetchModels(provider: AiProvider, config: AiProviderConfig): List<AiModel> {
        val validated = validate(provider, config, requireModel = false)
        val models = linkedMapOf<String, AiModel>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        repeat(25) {
            val root = get(request(provider, validated, cursor = cursor))
            val items = root.get(if (provider == AiProvider.GEMINI) "models" else "data")
                ?.takeIf { it.isArray } ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
            items.forEach { item ->
                model(provider, item)?.let { models.putIfAbsent(it.id, it) }
            }
            cursor = when (provider) {
                AiProvider.GEMINI -> root.text("nextPageToken")
                AiProvider.CLAUDE -> if (root.path("has_more").asBoolean(false)) {
                    root.text("last_id") ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
                } else null
                AiProvider.OPENAI -> null
            }
            if (cursor == null) return models.values.sortedBy { it.id }
            if (!cursors.add(cursor)) throw AiProviderException(AiFailure.INVALID_RESPONSE)
        }
        throw AiProviderException(AiFailure.INVALID_RESPONSE)
    }

    override suspend fun testConnection(provider: AiProvider, config: AiProviderConfig): AiModel {
        val validated = validate(provider, config, requireModel = true)
        val root = try {
            get(request(provider, validated, modelId = validated.model))
        } catch (e: AiProviderException) {
            // Some compatible services (including DeepSeek) expose only the model catalog.
            if (validated.baseUrl.isNotEmpty() && e.statusCode in listOf(404, 405, 501)) {
                return fetchModels(provider, validated).firstOrNull { it.id == validated.model }
                    ?: throw AiProviderException(AiFailure.NOT_FOUND)
            }
            throw e
        }
        if (provider == AiProvider.GEMINI && !supportsGeneration(root)) {
            throw AiProviderException(AiFailure.UNSUPPORTED_MODEL)
        }
        return model(provider, root) ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
    }

    private fun validate(provider: AiProvider, config: AiProviderConfig, requireModel: Boolean): AiProviderConfig {
        val baseUrl = config.baseUrl.trim()
        if (baseUrl.isNotEmpty() && aiApiBaseUrlOrNull(baseUrl) == null) throw AiProviderException(AiFailure.INVALID_URL)
        val key = config.apiKey.trim()
        if (key.isEmpty() && baseUrl.isEmpty()) throw AiProviderException(AiFailure.MISSING_KEY)
        if (key.any { it <= ' ' || it > '~' }) throw AiProviderException(AiFailure.INVALID_KEY)
        val model = config.model.trim().let { if (provider == AiProvider.GEMINI) it.removePrefix("models/") else it }
        if (requireModel) {
            if (model.isEmpty()) throw AiProviderException(AiFailure.MISSING_MODEL)
            if (model == "." || model == ".." || model.any { it.isWhitespace() || it.isISOControl() }) {
                throw AiProviderException(AiFailure.INVALID_MODEL)
            }
        }
        return config.copy(apiKey = key, model = model, baseUrl = baseUrl)
    }

    private fun request(provider: AiProvider, config: AiProviderConfig, modelId: String? = null, cursor: String? = null): Request {
        val base = if (config.baseUrl.isEmpty()) endpoint(provider) else {
            aiApiBaseUrlOrNull(config.baseUrl) ?: throw AiProviderException(AiFailure.INVALID_URL)
        }
        val url = base.newBuilder().addPathSegment("models")
        if (modelId != null) url.addPathSegment(modelId)
        if (modelId == null) when (provider) {
            AiProvider.GEMINI -> {
                url.addQueryParameter("pageSize", "1000")
                if (cursor != null) url.addQueryParameter("pageToken", cursor)
            }
            AiProvider.CLAUDE -> {
                url.addQueryParameter("limit", "1000")
                if (cursor != null) url.addQueryParameter("after_id", cursor)
            }
            AiProvider.OPENAI -> Unit
        }
        return Request.Builder().url(url.build()).header("Accept", "application/json").apply {
            when (provider) {
                AiProvider.OPENAI -> if (config.apiKey.isNotEmpty()) header("Authorization", "Bearer ${config.apiKey}")
                AiProvider.GEMINI -> if (config.apiKey.isNotEmpty()) header("x-goog-api-key", config.apiKey)
                AiProvider.CLAUDE -> {
                    if (config.apiKey.isNotEmpty()) header("x-api-key", config.apiKey)
                    header("anthropic-version", "2023-06-01")
                }
            }
        }.build()
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isTextual }
        ?.asText()?.takeIf { it.isNotBlank() }

    private fun supportsGeneration(item: JsonNode): Boolean =
        item.path("supportedGenerationMethods").any { it.asText() == "generateContent" }

    private fun model(provider: AiProvider, item: JsonNode): AiModel? {
        val id = if (provider == AiProvider.GEMINI) {
            if (!supportsGeneration(item)) return null
            item.text("name")?.removePrefix("models/")
        } else item.text("id")
        if (id.isNullOrBlank()) return null
        val name = item.text(if (provider == AiProvider.GEMINI) "displayName" else "display_name")
            ?: (if (provider != AiProvider.GEMINI) item.text("name") else null) ?: id
        return AiModel(id, name)
    }

    private fun json(response: Response): JsonNode {
        val source = response.body.source()
        if (source.request(2_000_001)) throw AiProviderException(AiFailure.INVALID_RESPONSE)
        return DataStore.mapper.readTree(source.readUtf8())?.takeIf { it.isObject }
            ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
    }

    private fun invalidArgument(response: Response): AiFailure {
        val root = json(response)
        val invalidKeyReasons = setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_NOT_FOUND")
        return if (root.path("error").path("details").any { it.path("reason").asText() in invalidKeyReasons }) {
            AiFailure.AUTHENTICATION
        } else AiFailure.INVALID_RESPONSE
    }

    private suspend fun get(request: Request): JsonNode = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AiProviderException(
                    if (e is InterruptedIOException) AiFailure.TIMEOUT else AiFailure.NETWORK
                ))
            }

            override fun onResponse(call: Call, response: Response) {
                // Read while suspended so cancellation also interrupts slow response bodies.
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw AiProviderException(when (it.code) {
                            400 -> invalidArgument(it)
                            401 -> AiFailure.AUTHENTICATION
                            403 -> AiFailure.FORBIDDEN
                            404 -> AiFailure.NOT_FOUND
                            429 -> AiFailure.RATE_LIMIT
                            in 500..599 -> AiFailure.SERVER
                            else -> AiFailure.INVALID_RESPONSE
                        }, it.code)
                        json(it)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(when (e) {
                        is AiProviderException -> e
                        is InterruptedIOException -> AiProviderException(AiFailure.TIMEOUT)
                        is JsonProcessingException -> AiProviderException(AiFailure.INVALID_RESPONSE)
                        is IOException -> AiProviderException(AiFailure.NETWORK)
                        else -> AiProviderException(AiFailure.INVALID_RESPONSE)
                    })
                }
            }
        })
    }
}
