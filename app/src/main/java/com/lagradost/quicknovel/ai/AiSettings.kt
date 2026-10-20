package com.lagradost.quicknovel.ai

import java.util.UUID

enum class AiProvider(val displayName: String, internal val apiUrl: String) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/"),
    OPENAI("OpenAI", "https://api.openai.com/v1/"),
    CLAUDE("Claude", "https://api.anthropic.com/v1/"),
}

data class AiProviderConfig(val apiKey: String = "", val model: String = "", val baseUrl: String = "") {
    override fun toString(): String = "AiProviderConfig(apiKey=[redacted], model=$model, baseUrl=${if (baseUrl.isBlank()) "[default]" else "[custom]"})"
}

data class AiKeyProfile(
    val id: String,
    val name: String = "",
    val config: AiProviderConfig = AiProviderConfig(),
)

data class AiSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val profiles: Map<AiProvider, List<AiKeyProfile>> = emptyMap(),
    val selectedProfiles: Map<AiProvider, String> = emptyMap(),
) {
    fun keyProfiles(provider: AiProvider = this.provider): List<AiKeyProfile> = profiles[provider]
        ?.takeIf { it.isNotEmpty() } ?: listOf(AiKeyProfile("default-${provider.name}"))

    fun profile(provider: AiProvider = this.provider): AiKeyProfile = keyProfiles(provider).let { profiles ->
        profiles.firstOrNull { it.id == selectedProfiles[provider] } ?: profiles.first()
    }

    fun config(provider: AiProvider = this.provider): AiProviderConfig = profile(provider).config

    fun withConfig(config: AiProviderConfig): AiSettings = withProfile(profile().copy(config = config))

    fun withProfile(profile: AiKeyProfile): AiSettings = copy(
        profiles = profiles + (provider to keyProfiles().map { if (it.id == profile.id) profile else it }),
        selectedProfiles = selectedProfiles + (provider to profile().id),
    )

    fun addProfile(): AiSettings {
        val added = AiKeyProfile(UUID.randomUUID().toString())
        // An untouched provider's empty initial editor is not a saved key.
        val existing = if (provider !in profiles) emptyList() else keyProfiles()
        return copy(profiles = profiles + (provider to existing + added),
            selectedProfiles = selectedProfiles + (provider to added.id))
    }

    fun selectProfile(id: String): AiSettings = if (keyProfiles().any { it.id == id }) {
        copy(selectedProfiles = selectedProfiles + (provider to id))
    } else this

    fun removeProfile(id: String): AiSettings {
        if (keyProfiles().none { it.id == id }) return this
        val remaining = keyProfiles().filterNot { it.id == id }.ifEmpty {
            listOf(AiKeyProfile(UUID.randomUUID().toString()))
        }
        val selected = profile().id.takeIf { current -> remaining.any { it.id == current } } ?: remaining.first().id
        return copy(profiles = profiles + (provider to remaining),
            selectedProfiles = selectedProfiles + (provider to selected))
    }

    fun normalized(): AiSettings = copy(profiles = profiles.mapValues { (provider, profiles) ->
        profiles.map { profile -> profile.copy(name = profile.name.trim(), config = profile.config.let { config ->
            config.copy(apiKey = config.apiKey.trim(), baseUrl = config.baseUrl.trim().let {
                if (it.isEmpty()) "" else aiApiBaseUrlOrNull(it)?.toString() ?: it
            }, model = config.model.trim().let {
                if (provider == AiProvider.GEMINI) it.removePrefix("models/") else it
            })
        }) }
    })
}

data class AiModel(val id: String, val name: String = id)

enum class AiFailure {
    MISSING_KEY, INVALID_KEY, MISSING_MODEL, INVALID_MODEL, INVALID_URL, AUTHENTICATION, FORBIDDEN,
    NOT_FOUND, RATE_LIMIT, SERVER, NETWORK, TIMEOUT, INVALID_RESPONSE, UNSUPPORTED_MODEL,
    LOAD_SETTINGS, SAVE_SETTINGS, EMPTY_CHAPTER, REQUEST_TOO_LARGE, REQUEST_REJECTED, RESPONSE_BLOCKED, EMPTY_RESPONSE, SOURCE_CHANGED,
}

class AiProviderException(val failure: AiFailure, val statusCode: Int? = null) : Exception(failure.name)

interface AiProviderApi {
    suspend fun fetchModels(provider: AiProvider, config: AiProviderConfig): List<AiModel>
    suspend fun testConnection(provider: AiProvider, config: AiProviderConfig): AiModel
}

interface AiSettingsPersistence {
    suspend fun load(): AiSettings
    suspend fun save(settings: AiSettings)
}
