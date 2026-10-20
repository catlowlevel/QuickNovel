package com.lagradost.quicknovel.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiSummaryKeySelectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chapter = AiChapter("harbor", "chapter-1", "Harbor", "First arrival", "A ship arrives before dawn.")
    private val primary = AiKeyProfile("primary", "Personal", AiProviderConfig("fixture-primary", "primary-model"))
    private val alternate =
        AiKeyProfile(
            "alternate",
            "Custom server",
            AiProviderConfig("fixture-alternate", "alternate-model", "https://example.com/v1/"),
        )

    private fun settings() =
        AiSettings(
            profiles = mapOf(AiProvider.GEMINI to listOf(primary), AiProvider.OPENAI to listOf(alternate)),
            selectedProfiles = mapOf(AiProvider.GEMINI to primary.id),
        )

    private class Store(var value: AiSettings) : AiSettingsPersistence {
        var loader: suspend () -> AiSettings = { value }

        override suspend fun load() = loader()

        override suspend fun save(settings: AiSettings) = error("Reader selection must not rewrite global settings")
    }

    private class Api : AiSummaryApi {
        val calls = mutableListOf<AiSummaryPlan>()
        var answer: suspend () -> AiSummaryOutput = { AiSummaryOutput("The ship reaches the harbor.") }

        override suspend fun summarize(
            provider: AiProvider,
            config: AiProviderConfig,
            chapter: AiChapter,
            language: String,
        ): AiSummaryOutput {
            calls += AiSummaryPlan(chapter, provider, "", config, language)
            return answer()
        }
    }

    private fun AiSummaryController.confirm() = state.value as AiSummaryState.Confirm

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun selectorListsSavedProfilesAcrossProvidersAndUsesTheChosenKeyModelAndEndpoint() {
        val store = Store(settings())
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        assertEquals(listOf(primary.id, alternate.id), controller.savedKeys.map { it.id })
        assertFalse(controller.savedKeys.toString().contains("fixture-"))
        assertEquals(primary.id, controller.selectedKey!!.id)
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        val plan = controller.confirm().plan
        assertEquals(AiProvider.OPENAI, plan.provider)
        assertEquals(alternate.name, plan.profileName)
        assertEquals(alternate.config, plan.config)
        assertEquals(chapter, plan.chapter)
        assertEquals("en", plan.language)
        assertTrue(api.calls.isEmpty())
        assertEquals(settings(), store.value)
        controller.generate()
        assertEquals(alternate.config, api.calls.single().config)
        assertEquals(AiProvider.OPENAI, api.calls.single().provider)
    }

    @Test
    fun continuousRunKeepsTheChosenConfigurationEvenWhenGlobalSettingsOrSelectionAttemptsChange() {
        val following = chapter.copy(chapterId = "chapter-2", title = "Second arrival")
        val store = Store(settings())
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.attachChapterSource(
            object : AiSummaryChapterSource {
                override val changes = MutableStateFlow(0L)

                override suspend fun loadedAfter(after: AiChapter) = listOf(following)

                override suspend fun next(after: AiChapter) =
                    if (after == chapter) AiNextSummaryChapter.Loaded(following) else AiNextSummaryChapter.End
            }
        )
        controller.open(chapter, "en")
        controller.setContinuous(true)
        val previous = controller.confirm()
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        assertTrue(controller.confirm().continuous)
        assertEquals(previous.following, controller.confirm().following)
        assertEquals(previous.knownInputTokens, controller.confirm().knownInputTokens)
        controller.generate()
        val first = controller.state.value as AiSummaryState.Ready
        store.value = settings().copy(provider = AiProvider.CLAUDE)
        controller.selectKey(AiProvider.GEMINI, primary.id)
        assertSame(first, controller.state.value)
        controller.acknowledge(first)
        val second = controller.state.value as AiSummaryState.Ready
        controller.acknowledge(second)
        assertEquals(listOf(chapter, following), api.calls.map { it.chapter })
        assertTrue(api.calls.all { it.provider == AiProvider.OPENAI && it.config == alternate.config })
        assertTrue(controller.state.value is AiSummaryState.Finished)
    }

    @Test
    fun selectedKeySurvivesRetryRegenerationAndLaterChaptersInTheSameReader() {
        val store = Store(settings())
        val api = Api().apply { answer = { throw AiProviderException(AiFailure.RATE_LIMIT) } }
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        controller.generate()
        assertTrue(controller.state.value is AiSummaryState.Failed)
        controller.retry()
        assertEquals(alternate.config, controller.confirm().plan.config)
        api.answer = { AiSummaryOutput("A new recap.") }
        controller.generate()
        controller.regenerate()
        assertEquals(alternate.config, controller.confirm().plan.config)
        assertTrue(controller.confirm().regenerating)
        controller.close()
        controller.open(chapter.copy(chapterId = "chapter-2"), "en")
        assertEquals(alternate.config, controller.confirm().plan.config)
        assertEquals(settings(), store.value)
    }

    @Test
    fun selectingARegenerationKeyPreservesTheSavedSummaryUntilExplicitGeneration() {
        val store = Store(settings())
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.generate()
        val original = (controller.state.value as AiSummaryState.Ready).output
        controller.close()
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.regenerate()
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        assertTrue(controller.confirm().regenerating)
        assertEquals(1, api.calls.size)
        controller.close()
        controller.open(chapter, "en")
        controller.reuse()
        assertEquals(original, (controller.state.value as AiSummaryState.Ready).output)
        assertEquals(1, api.calls.size)
        controller.regenerate()
        assertEquals(alternate.config, controller.confirm().plan.config)
    }

    @Test
    fun anotherSavedKeyCanRecoverAnInvalidDefaultWithoutOpeningSettingsOrSendingARequest() {
        val store =
            Store(
                settings()
                    .copy(
                        profiles =
                            settings().profiles +
                                (AiProvider.GEMINI to listOf(primary.copy(config = AiProviderConfig())))
                    )
            )
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        assertEquals(AiFailure.MISSING_KEY, (controller.state.value as AiSummaryState.Failed).reason)
        assertEquals(2, controller.savedKeys.size)
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        assertEquals(alternate.config, controller.confirm().plan.config)
        val confirmed = controller.state.value
        controller.selectKey(AiProvider.CLAUDE, "not-saved")
        assertSame(confirmed, controller.state.value)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aRemovedReaderSelectionDoesNotSilentlySendWithTheGlobalDefault() {
        val store = Store(settings())
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        controller.close()
        store.value = settings().copy(profiles = mapOf(AiProvider.GEMINI to listOf(primary)))
        controller.open(chapter, "en")
        assertEquals(AiFailure.MISSING_KEY, (controller.state.value as AiSummaryState.Failed).reason)
        assertNull(controller.selectedKey)
        controller.generate()
        assertTrue(api.calls.isEmpty())
        controller.selectKey(AiProvider.GEMINI, primary.id)
        assertEquals(primary.config, controller.confirm().plan.config)
    }

    @Test
    fun aLateCancelledSettingsLoadCannotReplaceTheKeysForTheCurrentPopup() {
        val pending = CompletableDeferred<AiSettings>()
        val store = Store(settings()).apply { loader = { withContext(NonCancellable) { pending.await() } } }
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        store.loader = { store.value }
        val next = chapter.copy(chapterId = "chapter-2")
        controller.open(next, "en")
        controller.selectKey(AiProvider.OPENAI, alternate.id)
        pending.complete(AiSettings())
        assertEquals(next, controller.confirm().plan.chapter)
        assertEquals(alternate.id, controller.selectedKey!!.id)
        assertEquals(2, controller.savedKeys.size)
        assertTrue(api.calls.isEmpty())
    }
}
