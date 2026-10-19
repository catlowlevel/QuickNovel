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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
) : AiProviderApi, AiSummaryApi {
    // Generation can take longer, and a transport retry could repeat a paid request.
    private val generationClient = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp can follow Retry-After: 0 on HTTP errors independently of connection retries.
            if (response.code == 408 || response.code == 503) response.newBuilder()
                .header("Retry-After", Int.MAX_VALUE.toString()).build() else response
        }
        .callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput {
        validateSummaryChapter(chapter)
        val validated = validatedAiConfig(provider, config, requireModel = true)
        val base = if (validated.baseUrl.isEmpty()) endpoint(provider) else aiApiBaseUrlOrNull(validated.baseUrl)!!
        val instruction = summaryInstruction(language)
        val source = summarySource(chapter)
        val body = DataStore.mapper.createObjectNode().apply {
            when (provider) {
                AiProvider.OPENAI -> {
                    put("model", validated.model)
                    putArray("messages").apply {
                        addObject().put("role", "system").put("content", instruction)
                        addObject().put("role", "user").put("content", source)
                    }
                    if (base.host == "api.openai.com") {
                        put("max_completion_tokens", 8192)
                        put("store", false)
                    } else put("max_tokens", 8192)
                }
                AiProvider.GEMINI -> {
                    putObject("systemInstruction").putArray("parts").addObject().put("text", instruction)
                    putArray("contents").addObject().apply {
                        put("role", "user")
                        putArray("parts").addObject().put("text", source)
                    }
                    putObject("generationConfig").put("maxOutputTokens", 8192)
                }
                AiProvider.CLAUDE -> {
                    put("model", validated.model)
                    put("system", instruction)
                    put("max_tokens", 8192)
                    putArray("messages").addObject().put("role", "user").put("content", source)
                }
            }
        }
        val url = base.newBuilder().apply {
            when (provider) {
                AiProvider.OPENAI -> addPathSegment("chat").addPathSegment("completions")
                AiProvider.GEMINI -> addPathSegment("models").addPathSegment("${validated.model}:generateContent")
                AiProvider.CLAUDE -> addPathSegment("messages")
            }
        }.build()
        val request = request(provider, validated).newBuilder().url(url)
            .post(DataStore.mapper.writeValueAsBytes(body).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return summaryOutput(provider, get(request, generationClient, generation = true))
    }

    private fun summaryOutput(provider: AiProvider, root: JsonNode): AiSummaryOutput {
        val text: String
        val truncated: Boolean
        when (provider) {
            AiProvider.OPENAI -> {
                val choice = root.path("choices").firstOrNull() ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
                val reason = choice.path("finish_reason").asText()
                val message = choice.path("message")
                if (reason == "content_filter" || !message.path("refusal").asText("").isNullOrBlank()) {
                    throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
                }
                if (reason !in setOf("stop", "length")) throw AiProviderException(AiFailure.INVALID_RESPONSE)
                text = message.text("content") ?: ""
                truncated = reason == "length"
            }
            AiProvider.GEMINI -> {
                if (root.path("promptFeedback").text("blockReason") != null) throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
                val candidate = root.path("candidates").firstOrNull() ?: throw AiProviderException(AiFailure.INVALID_RESPONSE)
                val reason = candidate.path("finishReason").asText()
                if (reason !in setOf("STOP", "MAX_TOKENS")) throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
                text = candidate.path("content").path("parts").filterNot { it.path("thought").asBoolean(false) }
                    .mapNotNull { it.text("text") }.joinToString("\n")
                truncated = reason == "MAX_TOKENS"
            }
            AiProvider.CLAUDE -> {
                val reason = root.path("stop_reason").asText()
                if (reason == "refusal") throw AiProviderException(AiFailure.RESPONSE_BLOCKED)
                if (reason !in setOf("end_turn", "stop_sequence", "max_tokens")) throw AiProviderException(AiFailure.INVALID_RESPONSE)
                text = root.path("content").filter { it.path("type").asText() == "text" }
                    .mapNotNull { it.text("text") }.joinToString("\n")
                truncated = reason == "max_tokens"
            }
        }
        if (text.isBlank()) throw AiProviderException(AiFailure.EMPTY_RESPONSE)
        return AiSummaryOutput(text.trim(), truncated)
    }

    override suspend fun fetchModels(provider: AiProvider, config: AiProviderConfig): List<AiModel> {
        val validated = validatedAiConfig(provider, config, requireModel = false)
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
        val validated = validatedAiConfig(provider, config, requireModel = true)
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

    private suspend fun get(request: Request, transport: OkHttpClient = client, generation: Boolean = false): JsonNode = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
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
                            400 -> invalidArgument(it).let { reason ->
                                if (generation && reason == AiFailure.INVALID_RESPONSE) AiFailure.REQUEST_REJECTED else reason
                            }
                            401 -> AiFailure.AUTHENTICATION
                            403 -> AiFailure.FORBIDDEN
                            404 -> AiFailure.NOT_FOUND
                            408 -> AiFailure.TIMEOUT
                            413 -> AiFailure.REQUEST_TOO_LARGE
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
