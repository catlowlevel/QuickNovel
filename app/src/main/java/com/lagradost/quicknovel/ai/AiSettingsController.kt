package com.lagradost.quicknovel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AiSettingsOperation { FETCH_MODELS, TEST_CONNECTION }

sealed interface AiSettingsNotice {
    data object Saved : AiSettingsNotice
    data object NoModels : AiSettingsNotice
    data class Connected(val model: AiModel) : AiSettingsNotice
    data class Failed(val reason: AiFailure) : AiSettingsNotice
}

data class AiSettingsState(
    val settings: AiSettings = AiSettings(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val operation: AiSettingsOperation? = null,
    val models: List<AiModel> = emptyList(),
    val notice: AiSettingsNotice? = null,
)

/** Retains drafts across provider switches; edits invalidate outstanding credential/model checks. */
class AiSettingsController(
    private val scope: CoroutineScope,
    private val store: AiSettingsPersistence,
    private val api: AiProviderApi,
) {
    private val mutableState = MutableStateFlow(AiSettingsState())
    val state = mutableState.asStateFlow()
    private var persisted = AiSettings()
    private var requestJob: Job? = null
    private var revision = 0L

    init {
        scope.launch {
            try {
                persisted = store.load()
                mutableState.value = AiSettingsState(settings = persisted, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = AiSettingsState(loading = false,
                    notice = AiSettingsNotice.Failed(AiFailure.LOAD_SETTINGS))
            }
        }
    }

    fun selectProvider(provider: AiProvider) {
        val current = state.value
        if (current.settings.provider == provider) return
        edit(current.settings.copy(provider = provider), clearModels = true)
    }

    fun setApiKey(value: String) {
        edit(state.value.settings.withConfig(state.value.settings.config().copy(apiKey = value)), clearModels = true)
    }

    fun setModel(value: String) {
        edit(state.value.settings.withConfig(state.value.settings.config().copy(model = value)))
    }

    fun addKey() {
        edit(state.value.settings.addProfile(), clearModels = true)
    }

    fun selectKey(id: String) {
        if (state.value.settings.profile().id == id) return
        edit(state.value.settings.selectProfile(id), clearModels = true)
    }

    fun renameKey(name: String) {
        val settings = state.value.settings
        edit(settings.withProfile(settings.profile().copy(name = name)))
    }

    fun deleteKey() {
        edit(state.value.settings.removeProfile(state.value.settings.profile().id), clearModels = true)
    }

    private fun edit(settings: AiSettings, clearModels: Boolean = false) {
        if (state.value.loading || state.value.saving) return
        cancelRequest()
        mutableState.value = state.value.copy(settings = settings, dirty = settings != persisted,
            models = if (clearModels) emptyList() else state.value.models, notice = null)
    }

    fun cancelRequest() {
        revision++
        requestJob?.cancel()
        requestJob = null
        mutableState.value = state.value.copy(operation = null)
    }

    fun fetchModels() = request(AiSettingsOperation.FETCH_MODELS)
    fun testConnection() = request(AiSettingsOperation.TEST_CONNECTION)

    private fun request(operation: AiSettingsOperation) {
        if (state.value.loading || state.value.saving) return
        cancelRequest()
        val token = revision
        val settings = state.value.settings.normalized()
        mutableState.value = state.value.copy(operation = operation, notice = null)
        requestJob = scope.launch {
            try {
                if (operation == AiSettingsOperation.FETCH_MODELS) {
                    val models = api.fetchModels(settings.provider, settings.config())
                    if (revision == token) mutableState.value = state.value.copy(operation = null,
                        models = models, notice = if (models.isEmpty()) AiSettingsNotice.NoModels else null)
                } else {
                    val model = api.testConnection(settings.provider, settings.config())
                    if (revision == token) mutableState.value = state.value.copy(operation = null,
                        notice = AiSettingsNotice.Connected(model))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == token) mutableState.value = state.value.copy(operation = null,
                    notice = AiSettingsNotice.Failed((e as? AiProviderException)?.failure ?: AiFailure.NETWORK))
            }
        }
    }

    fun save() {
        if (state.value.loading || state.value.saving) return
        cancelRequest()
        val settings = state.value.settings.normalized()
        mutableState.value = state.value.copy(settings = settings, saving = true, notice = null)
        scope.launch {
            try {
                store.save(settings)
                persisted = settings
                mutableState.value = state.value.copy(saving = false, dirty = false, notice = AiSettingsNotice.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, dirty = settings != persisted,
                    notice = AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS))
            }
        }
    }
}
