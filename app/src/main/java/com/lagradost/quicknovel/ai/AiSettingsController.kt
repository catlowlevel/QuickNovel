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
    val viewedProvider: AiProvider = settings.provider,
    val viewedProfiles: Map<AiProvider, String> = emptyMap(),
    val editing: Boolean = false,
    val adding: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val operation: AiSettingsOperation? = null,
    val models: List<AiModel> = emptyList(),
    val notice: AiSettingsNotice? = null,
) {
    /** Editor navigation never changes the configuration selected for use. */
    val editor: AiSettings get() = settings.copy(provider = viewedProvider,
        selectedProfiles = settings.selectedProfiles + viewedProfiles)
    val isEditorSelected: Boolean get() = settings.provider == viewedProvider && settings.profile().id == editor.profile().id
}

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
    private var editorCheckpoint: AiSettings? = null

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
        if (current.loading || current.saving || current.viewedProvider == provider) return
        cancelRequest()
        mutableState.value = state.value.copy(viewedProvider = provider, models = emptyList(), notice = null)
    }

    fun openEditor(provider: AiProvider, id: String) {
        val current = state.value
        if (current.loading || current.saving || current.editing || current.settings.profiles[provider].orEmpty().none { it.id == id }) return
        editorCheckpoint = current.settings
        selectProvider(provider)
        selectKey(id)
        mutableState.value = state.value.copy(editing = true, adding = false, notice = null)
    }

    fun createKey(provider: AiProvider) {
        val current = state.value
        if (current.loading || current.saving || current.editing) return
        editorCheckpoint = current.settings
        selectProvider(provider)
        addKey()
        if (current.settings.profiles.values.all { it.isEmpty() }) useConfiguration()
        mutableState.value = state.value.copy(editing = true, adding = true, notice = null)
    }

    fun discardEditor() {
        val current = state.value
        if (!current.editing || current.saving) return
        cancelRequest()
        val restored = editorCheckpoint ?: persisted
        editorCheckpoint = null
        mutableState.value = state.value.copy(settings = restored, editing = false, adding = false,
            dirty = restored != persisted, models = emptyList(), notice = null)
    }

    fun activateKey(provider: AiProvider, id: String) {
        val current = state.value
        if (current.loading || current.saving || current.editing || current.settings.profiles[provider].orEmpty().none { it.id == id }) return
        if (current.settings.provider == provider && current.settings.profile().id == id) return
        edit(current.settings.copy(provider = provider).selectProfile(id), clearModels = true)
        saveSettings(rollbackOnFailure = true)
    }

    fun deleteEditedKey() {
        val current = state.value
        if (!current.editing || current.adding || current.saving || current.loading) return
        // Delete from the checkpoint so unfinished field edits never become saved side effects.
        val original = editorCheckpoint ?: return
        val provider = current.viewedProvider
        val remaining = original.profiles[provider].orEmpty().filterNot { it.id == current.editor.profile().id }
        var updated = original.copy(
            profiles = if (remaining.isEmpty()) original.profiles - provider else original.profiles + (provider to remaining),
            selectedProfiles = if (remaining.isEmpty()) original.selectedProfiles - provider else original.selectedProfiles +
                (provider to (original.profile(provider).id.takeIf { id -> remaining.any { it.id == id } } ?: remaining.first().id)),
        )
        if (updated.provider == provider && remaining.isEmpty()) {
            updated.profiles.entries.firstOrNull { it.value.isNotEmpty() }?.let { updated = updated.copy(provider = it.key) }
        }
        cancelRequest()
        mutableState.value = state.value.copy(saving = true, notice = null)
        scope.launch {
            try {
                store.save(updated)
                persisted = updated
                editorCheckpoint = null
                mutableState.value = state.value.copy(settings = updated, editing = false, adding = false, saving = false,
                    dirty = false, models = emptyList(), notice = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, notice = AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS))
            }
        }
    }

    fun useConfiguration() {
        val current = state.value
        if (current.isEditorSelected) return
        edit(current.settings.copy(provider = current.viewedProvider).selectProfile(current.editor.profile().id))
    }

    private fun editProfile(profile: AiKeyProfile, clearModels: Boolean = false) {
        val current = state.value
        edit(current.settings.copy(profiles = current.editor.withProfile(profile).profiles), clearModels)
    }

    fun setApiKey(value: String) {
        val profile = state.value.editor.profile()
        editProfile(profile.copy(config = profile.config.copy(apiKey = value)), clearModels = true)
    }

    fun setModel(value: String) {
        val profile = state.value.editor.profile()
        editProfile(profile.copy(config = profile.config.copy(model = value)))
    }

    fun setBaseUrl(value: String) {
        val profile = state.value.editor.profile()
        editProfile(profile.copy(config = profile.config.copy(baseUrl = value)), clearModels = true)
    }

    fun addKey() {
        val current = state.value
        if (current.loading || current.saving) return
        val added = current.editor.addProfile()
        edit(current.settings.copy(profiles = added.profiles), clearModels = true)
        mutableState.value = state.value.copy(viewedProfiles = current.viewedProfiles + (current.viewedProvider to added.profile().id))
    }

    fun selectKey(id: String) {
        val current = state.value
        if (current.loading || current.saving || current.editor.profile().id == id || current.editor.keyProfiles().none { it.id == id }) return
        cancelRequest()
        mutableState.value = state.value.copy(viewedProfiles = current.viewedProfiles + (current.viewedProvider to id),
            models = emptyList(), notice = null)
    }

    fun renameKey(name: String) {
        editProfile(state.value.editor.profile().copy(name = name))
    }

    fun deleteKey() {
        val current = state.value
        if (current.loading || current.saving) return
        val removed = current.settings.copy(provider = current.viewedProvider).removeProfile(current.editor.profile().id)
        edit(removed.copy(provider = current.settings.provider), clearModels = true)
        mutableState.value = state.value.copy(viewedProfiles = current.viewedProfiles + (current.viewedProvider to removed.profile().id))
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
        val settings = state.value.editor.normalized()
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

    fun save() = saveSettings()

    private fun saveSettings(rollbackOnFailure: Boolean = false) {
        if (state.value.loading || state.value.saving || !state.value.dirty) return
        cancelRequest()
        val settings = state.value.settings.normalized()
        mutableState.value = state.value.copy(settings = settings, saving = true, notice = null)
        scope.launch {
            try {
                store.save(settings)
                persisted = settings
                editorCheckpoint = null
                mutableState.value = state.value.copy(saving = false, editing = false, adding = false, dirty = false,
                    notice = AiSettingsNotice.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false,
                    settings = if (rollbackOnFailure) persisted else settings,
                    dirty = !rollbackOnFailure && settings != persisted,
                    notice = AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS))
            }
        }
    }
}
