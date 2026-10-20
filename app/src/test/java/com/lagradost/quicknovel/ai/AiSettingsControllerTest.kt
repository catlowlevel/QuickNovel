package com.lagradost.quicknovel.ai

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiSettingsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class Store(var value: AiSettings = AiSettings()) : AiSettingsPersistence {
        var writes = 0
        var failSave = false
        var failLoad = false

        override suspend fun load(): AiSettings {
            if (failLoad) throw IOException("unreadable")
            return value
        }

        override suspend fun save(settings: AiSettings) {
            if (failSave) throw IOException("unwritable")
            writes++
            value = settings
        }
    }

    private class Api : AiProviderApi {
        var models: suspend (AiProvider, AiProviderConfig) -> List<AiModel> = { _, _ -> listOf(AiModel("found-model")) }
        var check: suspend (AiProvider, AiProviderConfig) -> AiModel = { _, config -> AiModel(config.model) }

        override suspend fun fetchModels(provider: AiProvider, config: AiProviderConfig) = models(provider, config)

        override suspend fun testConnection(provider: AiProvider, config: AiProviderConfig) = check(provider, config)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun switchingProvidersKeepsDraftsAndSavingRestoresAllOfThemWithTheActiveProvider() {
        val store = Store()
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey(" gemini-key ")
        controller.setModel(" models/gemini-test ")
        controller.selectProvider(AiProvider.OPENAI)
        controller.setApiKey("openai-key")
        controller.setModel("chosen-openai")
        controller.selectProvider(AiProvider.GEMINI)
        assertEquals(" gemini-key ", controller.state.value.editor.config().apiKey)
        controller.selectProvider(AiProvider.OPENAI)
        controller.useConfiguration()
        controller.save()
        assertFalse(controller.state.value.dirty)
        assertEquals(AiSettingsNotice.Saved, controller.state.value.notice)
        assertEquals(AiProviderConfig("gemini-key", "gemini-test"), store.value.config(AiProvider.GEMINI))
        val reopened = AiSettingsController(scope, store, Api())
        assertEquals(AiProvider.OPENAI, reopened.state.value.settings.provider)
        assertEquals(AiProviderConfig("openai-key", "chosen-openai"), reopened.state.value.editor.config())
    }

    @Test
    fun fetchingAndTestingDraftsDoesNotSaveOrReplaceTheSelectedModel() {
        val store = Store()
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("draft-key")
        controller.setModel("manual-model")
        controller.fetchModels()
        assertEquals(listOf(AiModel("found-model")), controller.state.value.models)
        assertEquals("manual-model", controller.state.value.editor.config().model)
        controller.testConnection()
        assertEquals(AiSettingsNotice.Connected(AiModel("manual-model")), controller.state.value.notice)
        assertTrue(controller.state.value.dirty)
        assertEquals(0, store.writes)
        controller.setModel("another-model")
        assertNull(controller.state.value.notice)
    }

    @Test
    fun delayedModelResultsCannotPopulateAnotherProviderAfterSwitching() {
        val oldResult = CompletableDeferred<List<AiModel>>()
        val api =
            Api().apply {
                models = { provider, _ ->
                    if (provider == AiProvider.GEMINI) withContext(NonCancellable) { oldResult.await() }
                    else listOf(AiModel("openai-model"))
                }
            }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("gemini-key")
        controller.fetchModels()
        assertEquals(AiSettingsOperation.FETCH_MODELS, controller.state.value.operation)
        controller.selectProvider(AiProvider.OPENAI)
        controller.setApiKey("openai-key")
        controller.fetchModels()
        oldResult.complete(listOf(AiModel("old-gemini-model")))
        assertEquals(listOf(AiModel("openai-model")), controller.state.value.models)
        assertNull(controller.state.value.operation)
    }

    @Test
    fun editingCredentialsInvalidatesEvenALateSuccessfulConnectionCheck() {
        val oldResult = CompletableDeferred<AiModel>()
        val api = Api().apply { check = { _, _ -> withContext(NonCancellable) { oldResult.await() } } }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("old-key")
        controller.setModel("model")
        controller.testConnection()
        controller.setApiKey("new-key")
        oldResult.complete(AiModel("model"))
        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.operation)
        assertEquals("new-key", controller.state.value.editor.config().apiKey)
    }

    @Test
    fun failedSaveRetainsUnsavedDraftsAndCanBeRetried() {
        val store = Store().apply { failSave = true }
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("new-key")
        controller.save()
        assertEquals(AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS), controller.state.value.notice)
        assertTrue(controller.state.value.dirty)
        assertEquals("new-key", controller.state.value.editor.config().apiKey)
        assertEquals(0, store.writes)
        store.failSave = false
        controller.save()
        assertFalse(controller.state.value.dirty)
        assertEquals(1, store.writes)
    }

    @Test
    fun unreadableSettingsReportAnErrorWithoutOverwritingTheStore() {
        val store = Store().apply { failLoad = true }
        val controller = AiSettingsController(scope, store, Api())
        assertFalse(controller.state.value.loading)
        assertEquals(AiSettingsNotice.Failed(AiFailure.LOAD_SETTINGS), controller.state.value.notice)
        assertEquals(0, store.writes)
    }

    @Test
    fun cancellationClearsBusyStateAndProviderErrorsKeepTheDraft() {
        val api = Api().apply { check = { _, _ -> awaitCancellation() } }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("key")
        controller.setModel("model")
        controller.testConnection()
        controller.cancelRequest()
        assertNull(controller.state.value.operation)
        assertNull(controller.state.value.notice)
        api.check = { _, _ -> throw AiProviderException(AiFailure.AUTHENTICATION) }
        controller.testConnection()
        assertEquals(AiSettingsNotice.Failed(AiFailure.AUTHENTICATION), controller.state.value.notice)
        assertEquals("key", controller.state.value.editor.config().apiKey)
    }

    @Test
    fun clearingACredentialAndSavingActuallyRemovesItsPreviousValue() {
        val store = Store(AiSettings().withConfig(AiProviderConfig("old-key", "model")))
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("")
        controller.save()
        assertEquals("", store.value.config().apiKey)
        assertFalse(controller.state.value.dirty)
    }

    @Test
    fun multipleNamedKeysKeepIndependentModelsAndRequestsUseTheSelectedKey() {
        val store = Store()
        val used = mutableListOf<AiProviderConfig>()
        val api =
            Api().apply {
                models = { _, config ->
                    used += config
                    listOf(AiModel("fetched"))
                }
                check = { _, config ->
                    used += config
                    AiModel(config.model)
                }
            }
        val controller = AiSettingsController(scope, store, api)
        controller.setApiKey("personal-key")
        controller.setModel("personal-model")
        controller.renameKey("Personal")
        val personal = controller.state.value.editor.profile().id
        controller.addKey()
        assertEquals(AiProviderConfig(), controller.state.value.editor.config())
        controller.renameKey("Work")
        controller.setApiKey("work-key")
        controller.setModel("work-model")
        val work = controller.state.value.editor.profile().id
        controller.fetchModels()
        controller.selectKey(personal)
        assertTrue(controller.state.value.models.isEmpty())
        controller.testConnection()
        assertEquals(
            listOf(AiProviderConfig("work-key", "work-model"), AiProviderConfig("personal-key", "personal-model")),
            used,
        )
        controller.selectKey(work)
        controller.useConfiguration()
        controller.save()
        val reopened = AiSettingsController(scope, store, api)
        assertEquals(work, reopened.state.value.editor.profile().id)
        assertEquals(listOf("Personal", "Work"), reopened.state.value.settings.keyProfiles().map { it.name })
        reopened.selectKey(personal)
        assertEquals(AiProviderConfig("personal-key", "personal-model"), reopened.state.value.editor.config())
    }

    @Test
    fun switchingKeysInvalidatesLateChecksAndDoesNotReuseAnotherKeysModelCatalog() {
        val oldResult = CompletableDeferred<AiModel>()
        val api = Api().apply { check = { _, _ -> withContext(NonCancellable) { oldResult.await() } } }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("first")
        controller.setModel("first-model")
        val first = controller.state.value.editor.profile().id
        controller.addKey()
        controller.setApiKey("second")
        controller.setModel("second-model")
        controller.fetchModels()
        controller.testConnection()
        controller.selectKey(first)
        oldResult.complete(AiModel("second-model"))
        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.operation)
        assertTrue(controller.state.value.models.isEmpty())
        assertEquals(AiProviderConfig("first", "first-model"), controller.state.value.editor.config())
    }

    @Test
    fun deletingKeysRemovesTheirSavedCredentialsAndCanBeDiscardedBeforeSaving() {
        val store = Store()
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("first-key")
        controller.addKey()
        controller.setApiKey("second-key")
        controller.useConfiguration()
        controller.save()
        controller.deleteKey()
        assertTrue(controller.state.value.dirty)
        assertEquals(2, store.value.keyProfiles().size)
        assertEquals("first-key", controller.state.value.editor.config().apiKey)
        controller.save()
        assertEquals(1, store.value.keyProfiles().size)
        assertEquals("first-key", store.value.config().apiKey)
        controller.deleteKey()
        controller.save()
        assertEquals(AiProviderConfig(), store.value.config())
        assertFalse(store.value.toString().contains("first-key"))
    }

    @Test
    fun customUrlsAreSavedPerKeyAndRequestsUseTheSelectedEndpoints() {
        val store = Store()
        val used = mutableListOf<String>()
        val api =
            Api().apply {
                models = { _, config ->
                    used += config.baseUrl
                    emptyList()
                }
            }
        val controller = AiSettingsController(scope, store, api)
        controller.setApiKey("first")
        controller.setBaseUrl("https://first.example/v1")
        val first = controller.state.value.editor.profile().id
        controller.fetchModels()
        controller.addKey()
        assertEquals("", controller.state.value.editor.config().baseUrl)
        controller.setApiKey("second")
        controller.setBaseUrl("http://localhost:8080/api")
        val second = controller.state.value.editor.profile().id
        controller.fetchModels()
        controller.useConfiguration()
        controller.save()
        assertEquals(listOf("https://first.example/v1/", "http://localhost:8080/api/"), used)
        val reopened = AiSettingsController(scope, store, api)
        assertEquals(second, reopened.state.value.editor.profile().id)
        assertEquals("http://localhost:8080/api/", reopened.state.value.editor.config().baseUrl)
        reopened.selectKey(first)
        reopened.setBaseUrl("")
        reopened.save()
        assertEquals(second, store.value.profile().id)
        assertEquals("", store.value.selectProfile(first).config().baseUrl)
        assertEquals("http://localhost:8080/api/", store.value.config().baseUrl)
    }

    @Test
    fun browsingProvidersAndKeysDoesNotEnableSaveOrChangeTheSelectedConfiguration() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first-key", "first-model"))
                .addProfile()
                .withConfig(AiProviderConfig("second-key", "second-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        val second = initial.profile().id
        controller.selectProvider(AiProvider.OPENAI)
        assertFalse(controller.state.value.dirty)
        assertFalse(controller.state.value.isEditorSelected)
        controller.selectProvider(AiProvider.GEMINI)
        assertTrue(controller.state.value.isEditorSelected)
        controller.selectKey(initial.keyProfiles().first().id)
        assertFalse(controller.state.value.isEditorSelected)
        assertEquals(second, controller.state.value.settings.profile().id)
        assertFalse(controller.state.value.dirty)
        controller.selectKey("unknown-id")
        controller.save()
        assertEquals(0, store.writes)
        assertEquals(initial, controller.state.value.settings)
    }

    @Test
    fun editingAndTestingAnotherProviderDoesNotSelectItEvenWhenSaved() {
        val initial = AiSettings().withConfig(AiProviderConfig("active-key", "active-model"))
        val store = Store(initial)
        val requests = mutableListOf<Pair<AiProvider, AiProviderConfig>>()
        val api =
            Api().apply {
                check = { provider, config ->
                    requests += provider to config
                    AiModel(config.model)
                }
            }
        val controller = AiSettingsController(scope, store, api)
        controller.selectProvider(AiProvider.OPENAI)
        controller.setApiKey("draft-key")
        controller.setModel("draft-model")
        controller.testConnection()
        controller.save()
        assertEquals(listOf(AiProvider.OPENAI to AiProviderConfig("draft-key", "draft-model")), requests)
        assertEquals(AiProvider.GEMINI, store.value.provider)
        assertEquals(initial.config(), store.value.config())
        assertEquals(AiProviderConfig("draft-key", "draft-model"), store.value.config(AiProvider.OPENAI))
        assertEquals(AiProvider.OPENAI, controller.state.value.editor.provider)
        assertFalse(controller.state.value.isEditorSelected)
        controller.useConfiguration()
        assertTrue(controller.state.value.dirty)
        assertTrue(controller.state.value.isEditorSelected)
        assertEquals(AiProvider.GEMINI, store.value.provider)
        controller.save()
        val reopened = AiSettingsController(scope, store, api)
        assertEquals(AiProvider.OPENAI, reopened.state.value.settings.provider)
        assertTrue(reopened.state.value.isEditorSelected)
    }

    @Test
    fun addingEditingAndDeletingAnInactiveKeyPreservesTheActiveKey() {
        val initial = AiSettings().withConfig(AiProviderConfig("active-key", "active-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        controller.addKey()
        assertFalse(controller.state.value.isEditorSelected)
        controller.setApiKey("other-key")
        controller.setModel("other-model")
        controller.renameKey("Other")
        controller.save()
        val other = controller.state.value.editor.profile().id
        assertEquals(initial.profile().id, store.value.profile().id)
        assertEquals("other-key", store.value.selectProfile(other).config().apiKey)
        controller.deleteKey()
        controller.save()
        assertEquals(initial.profile().id, store.value.profile().id)
        assertEquals(initial.config(), store.value.config())
        assertEquals(1, store.value.keyProfiles().size)
    }

    @Test
    fun selectingAnotherKeyRequiresExplicitUseAndCanBeRevertedBeforeSaving() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first", "first-model"))
                .addProfile()
                .withConfig(AiProviderConfig("second", "second-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        val first = initial.keyProfiles().first().id
        controller.selectKey(first)
        controller.useConfiguration()
        assertTrue(controller.state.value.dirty)
        assertEquals(first, controller.state.value.settings.profile().id)
        assertEquals(initial, store.value)
        controller.selectKey(initial.profile().id)
        controller.useConfiguration()
        assertFalse(controller.state.value.dirty)
        assertEquals(initial, controller.state.value.settings)
        controller.selectKey(first)
        controller.useConfiguration()
        controller.save()
        assertEquals(first, AiSettingsController(scope, store, Api()).state.value.settings.profile().id)
    }

    @Test
    fun cancelledNewEditorLeavesNoProfileOrLateModelResults() {
        val initial = AiSettings().withConfig(AiProviderConfig("active-key", "active-model"))
        val store = Store(initial)
        val result = CompletableDeferred<List<AiModel>>()
        val api = Api().apply { models = { _, _ -> withContext(NonCancellable) { result.await() } } }
        val controller = AiSettingsController(scope, store, api)
        controller.createKey(AiProvider.OPENAI)
        assertTrue(controller.state.value.editing)
        assertTrue(controller.state.value.adding)
        controller.renameKey("Cancelled")
        controller.setApiKey("cancelled-key")
        controller.setBaseUrl("https://cancelled.example")
        controller.fetchModels()
        controller.discardEditor()
        result.complete(listOf(AiModel("cancelled-model")))
        assertEquals(initial, controller.state.value.settings)
        assertEquals(initial, store.value)
        assertFalse(controller.state.value.editing)
        assertFalse(controller.state.value.dirty)
        assertNull(controller.state.value.operation)
        assertNull(controller.state.value.notice)
        assertTrue(controller.state.value.models.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test
    fun existingEditorOpensCleanAndCancelRestoresNameCredentialsModelAndSelection() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first", "first-model"))
                .addProfile()
                .withConfig(AiProviderConfig("second", "second-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        val first = initial.keyProfiles().first().id
        controller.openEditor(AiProvider.GEMINI, "unknown")
        assertFalse(controller.state.value.editing)
        controller.openEditor(AiProvider.GEMINI, first)
        assertTrue(controller.state.value.editing)
        assertFalse(controller.state.value.adding)
        assertFalse(controller.state.value.dirty)
        assertEquals(initial.profile().id, controller.state.value.settings.profile().id)
        controller.renameKey("Changed")
        controller.setApiKey("changed")
        controller.setModel("changed-model")
        controller.setBaseUrl("https://changed.example")
        controller.discardEditor()
        assertEquals(initial, controller.state.value.settings)
        assertFalse(controller.state.value.editing)
        assertFalse(controller.state.value.dirty)
        assertEquals(0, store.writes)
    }

    @Test
    fun editorSaveFailureRetainsDraftAndSuccessfulRetryReturnsToList() {
        val store = Store().apply { failSave = true }
        val controller = AiSettingsController(scope, store, Api())
        controller.createKey(AiProvider.CLAUDE)
        controller.renameKey("Work")
        controller.setApiKey("work-key")
        controller.setModel("work-model")
        val id = controller.state.value.editor.profile().id
        controller.save()
        assertTrue(controller.state.value.editing)
        assertTrue(controller.state.value.adding)
        assertTrue(controller.state.value.dirty)
        assertEquals("work-key", controller.state.value.editor.config().apiKey)
        assertTrue(store.value.profiles.isEmpty())
        store.failSave = false
        controller.save()
        assertFalse(controller.state.value.editing)
        assertFalse(controller.state.value.adding)
        assertFalse(controller.state.value.dirty)
        assertEquals(AiProvider.CLAUDE, store.value.provider)
        assertEquals(id, store.value.profile().id)
        controller.openEditor(AiProvider.CLAUDE, id)
        controller.setApiKey("unsaved")
        controller.discardEditor()
        assertEquals("work-key", controller.state.value.settings.config().apiKey)
    }

    @Test
    fun listActivationPersistsDirectlyAndRollsBackIfPersistenceFails() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first", "first-model"))
                .copy(provider = AiProvider.OPENAI)
                .withConfig(AiProviderConfig("second", "second-model"))
        val store = Store(initial).apply { failSave = true }
        val controller = AiSettingsController(scope, store, Api())
        val first = initial.profile(AiProvider.GEMINI).id
        controller.activateKey(AiProvider.GEMINI, first)
        assertFalse(controller.state.value.editing)
        assertFalse(controller.state.value.dirty)
        assertEquals(initial, controller.state.value.settings)
        assertEquals(AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS), controller.state.value.notice)
        store.failSave = false
        controller.activateKey(AiProvider.GEMINI, first)
        assertEquals(AiProvider.GEMINI, store.value.provider)
        assertFalse(controller.state.value.editing)
        assertFalse(controller.state.value.dirty)
        controller.activateKey(AiProvider.GEMINI, first)
        controller.activateKey(AiProvider.CLAUDE, "unknown")
        assertEquals(1, store.writes)
    }

    @Test
    fun deletingActiveKeyFallsBackAcrossProvidersAndLastDeletionLeavesAnEmptyList() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first", "first-model"))
                .copy(provider = AiProvider.OPENAI)
                .withConfig(AiProviderConfig("second", "second-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        controller.openEditor(AiProvider.OPENAI, initial.profile().id)
        controller.setApiKey("unfinished")
        controller.deleteEditedKey()
        assertFalse(controller.state.value.editing)
        assertEquals(AiProvider.GEMINI, store.value.provider)
        assertEquals(initial.config(AiProvider.GEMINI), store.value.config())
        assertNull(store.value.profiles[AiProvider.OPENAI])
        assertNull(store.value.selectedProfiles[AiProvider.OPENAI])
        controller.openEditor(AiProvider.GEMINI, store.value.profile().id)
        controller.deleteEditedKey()
        assertFalse(controller.state.value.editing)
        assertTrue(store.value.profiles.isEmpty())
        assertEquals(AiProviderConfig(), store.value.config())
    }

    @Test
    fun failedDeletionPreservesTheEditorAndCancelledEditsNeverReachStorage() {
        val initial = AiSettings().withConfig(AiProviderConfig("saved", "saved-model"))
        val store = Store(initial).apply { failSave = true }
        val controller = AiSettingsController(scope, store, Api())
        controller.openEditor(AiProvider.GEMINI, initial.profile().id)
        controller.setApiKey("draft")
        controller.deleteEditedKey()
        assertTrue(controller.state.value.editing)
        assertEquals("draft", controller.state.value.editor.config().apiKey)
        assertEquals(initial, store.value)
        controller.discardEditor()
        assertEquals(initial, controller.state.value.settings)
    }

    @Test
    fun editorDeletionKeepsAnotherActiveKeyAndRejectsConcurrentListActions() {
        val initial =
            AiSettings()
                .withConfig(AiProviderConfig("first", "first-model"))
                .addProfile()
                .withConfig(AiProviderConfig("second", "second-model"))
        val store = Store(initial)
        val controller = AiSettingsController(scope, store, Api())
        controller.openEditor(AiProvider.GEMINI, initial.keyProfiles().first().id)
        controller.createKey(AiProvider.CLAUDE)
        controller.activateKey(AiProvider.GEMINI, initial.keyProfiles().first().id)
        assertEquals(initial, controller.state.value.settings)
        assertEquals(0, store.writes)
        controller.deleteEditedKey()
        assertEquals(initial.profile(), store.value.profile())
        assertEquals(1, store.value.keyProfiles().size)
    }

    @Test
    fun editingAnEndpointInvalidatesItsOldCatalogAndLateConnectionResults() {
        val oldCheck = CompletableDeferred<AiModel>()
        val api = Api().apply { check = { _, _ -> withContext(NonCancellable) { oldCheck.await() } } }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("key")
        controller.setModel("model")
        controller.setBaseUrl("https://old.example/v1")
        controller.fetchModels()
        controller.testConnection()
        controller.setBaseUrl("https://new.example/v1")
        assertTrue(controller.state.value.models.isEmpty())
        oldCheck.complete(AiModel("old-server-model"))
        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.operation)
        assertEquals("https://new.example/v1", controller.state.value.editor.config().baseUrl)
    }
}
