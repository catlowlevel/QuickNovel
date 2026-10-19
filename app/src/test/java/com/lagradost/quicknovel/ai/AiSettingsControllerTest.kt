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
    fun tearDown() { scope.cancel() }

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
        assertEquals(" gemini-key ", controller.state.value.settings.config().apiKey)
        controller.selectProvider(AiProvider.OPENAI)
        controller.save()
        assertFalse(controller.state.value.dirty)
        assertEquals(AiSettingsNotice.Saved, controller.state.value.notice)
        assertEquals(AiProviderConfig("gemini-key", "gemini-test"), store.value.config(AiProvider.GEMINI))
        val reopened = AiSettingsController(scope, store, Api())
        assertEquals(AiProvider.OPENAI, reopened.state.value.settings.provider)
        assertEquals(AiProviderConfig("openai-key", "chosen-openai"), reopened.state.value.settings.config())
    }

    @Test
    fun fetchingAndTestingDraftsDoesNotSaveOrReplaceTheSelectedModel() {
        val store = Store()
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("draft-key")
        controller.setModel("manual-model")
        controller.fetchModels()
        assertEquals(listOf(AiModel("found-model")), controller.state.value.models)
        assertEquals("manual-model", controller.state.value.settings.config().model)
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
        val api = Api().apply { models = { provider, _ ->
            if (provider == AiProvider.GEMINI) withContext(NonCancellable) { oldResult.await() }
            else listOf(AiModel("openai-model"))
        } }
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
        assertEquals("new-key", controller.state.value.settings.config().apiKey)
    }

    @Test
    fun failedSaveRetainsUnsavedDraftsAndCanBeRetried() {
        val store = Store().apply { failSave = true }
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("new-key")
        controller.save()
        assertEquals(AiSettingsNotice.Failed(AiFailure.SAVE_SETTINGS), controller.state.value.notice)
        assertTrue(controller.state.value.dirty)
        assertEquals("new-key", controller.state.value.settings.config().apiKey)
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
        assertEquals("key", controller.state.value.settings.config().apiKey)
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
        val api = Api().apply {
            models = { _, config -> used += config; listOf(AiModel("fetched")) }
            check = { _, config -> used += config; AiModel(config.model) }
        }
        val controller = AiSettingsController(scope, store, api)
        controller.setApiKey("personal-key")
        controller.setModel("personal-model")
        controller.renameKey("Personal")
        val personal = controller.state.value.settings.profile().id
        controller.addKey()
        assertEquals(AiProviderConfig(), controller.state.value.settings.config())
        controller.renameKey("Work")
        controller.setApiKey("work-key")
        controller.setModel("work-model")
        val work = controller.state.value.settings.profile().id
        controller.fetchModels()
        controller.selectKey(personal)
        assertTrue(controller.state.value.models.isEmpty())
        controller.testConnection()
        assertEquals(listOf(AiProviderConfig("work-key", "work-model"), AiProviderConfig("personal-key", "personal-model")), used)
        controller.selectKey(work)
        controller.save()
        val reopened = AiSettingsController(scope, store, api)
        assertEquals(work, reopened.state.value.settings.profile().id)
        assertEquals(listOf("Personal", "Work"), reopened.state.value.settings.keyProfiles().map { it.name })
        reopened.selectKey(personal)
        assertEquals(AiProviderConfig("personal-key", "personal-model"), reopened.state.value.settings.config())
    }

    @Test
    fun switchingKeysInvalidatesLateChecksAndDoesNotReuseAnotherKeysModelCatalog() {
        val oldResult = CompletableDeferred<AiModel>()
        val api = Api().apply { check = { _, _ -> withContext(NonCancellable) { oldResult.await() } } }
        val controller = AiSettingsController(scope, Store(), api)
        controller.setApiKey("first")
        controller.setModel("first-model")
        val first = controller.state.value.settings.profile().id
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
        assertEquals(AiProviderConfig("first", "first-model"), controller.state.value.settings.config())
    }

    @Test
    fun deletingKeysRemovesTheirSavedCredentialsAndCanBeDiscardedBeforeSaving() {
        val store = Store()
        val controller = AiSettingsController(scope, store, Api())
        controller.setApiKey("first-key")
        controller.addKey()
        controller.setApiKey("second-key")
        controller.save()
        controller.deleteKey()
        assertTrue(controller.state.value.dirty)
        assertEquals(2, store.value.keyProfiles().size)
        assertEquals("first-key", controller.state.value.settings.config().apiKey)
        controller.save()
        assertEquals(1, store.value.keyProfiles().size)
        assertEquals("first-key", store.value.config().apiKey)
        controller.deleteKey()
        controller.save()
        assertEquals(AiProviderConfig(), store.value.config())
        assertFalse(store.value.toString().contains("first-key"))
    }
}
