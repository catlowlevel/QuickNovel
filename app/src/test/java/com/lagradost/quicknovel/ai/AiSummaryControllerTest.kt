package com.lagradost.quicknovel.ai

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiSummaryControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chapter = AiChapter("novel-a", "chapter-4", "Novel A", "Chapter 4", "The first event. Then the second event.")
    private class Store : AiSettingsPersistence {
        var value = AiSettings().withConfig(AiProviderConfig("selected-key", "selected-model"))
        var loader: suspend () -> AiSettings = { value }
        override suspend fun load() = loader()
        override suspend fun save(settings: AiSettings) { error("Summaries never rewrite provider settings") }
    }
    private class Api : AiSummaryApi {
        val requests = mutableListOf<AiSummaryPlan>()
        var answer: suspend () -> AiSummaryOutput = { AiSummaryOutput("A concise recap.") }
        override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput {
            requests += AiSummaryPlan(chapter, provider, "", config, language)
            return answer()
        }
    }
    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun confirmationCapturesTheExactChapterProfileAndModelWithoutMakingAPaidCall() {
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en-US")
        val plan = (controller.state.value as AiSummaryState.Confirm).plan
        assertEquals(chapter, plan.chapter)
        assertEquals("selected-model", plan.config.model)
        assertEquals("en-US", plan.language)
        assertTrue(plan.inputTokens > estimateSummaryTokens(chapter.text))
        assertTrue(api.requests.isEmpty())
        // A settings change while confirmation is open cannot silently change the approved request.
        store.value = AiSettings(provider = AiProvider.OPENAI).withConfig(AiProviderConfig("different-key", "different-model"))
        controller.generate()
        assertEquals("selected-key", api.requests.single().config.apiKey)
        assertEquals(AiProvider.GEMINI, api.requests.single().provider)
        assertEquals(chapter, api.requests.single().chapter)
        assertTrue(controller.state.value is AiSummaryState.Ready)
    }

    @Test
    fun doubleGenerateCannotStartTwoPaidRequestsAndCancellationRejectsLateResults() {
        val result = CompletableDeferred<AiSummaryOutput>()
        val api = Api().apply { answer = { withContext(NonCancellable) { result.await() } } }
        val controller = AiSummaryController(scope, Store(), api)
        controller.open(chapter, "en")
        controller.generate()
        controller.generate()
        assertEquals(1, api.requests.size)
        assertTrue(controller.state.value is AiSummaryState.Running)
        controller.close()
        result.complete(AiSummaryOutput("Late recap"))
        assertEquals(AiSummaryState.Closed, controller.state.value)
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Confirm)
    }

    @Test
    fun completedResultsOfferReuseButNeverCrossChaptersBooksTextOrLanguages() {
        val api = Api()
        val controller = AiSummaryController(scope, Store(), api)
        controller.open(chapter, "en")
        controller.generate()
        controller.close()
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.generate() // Only the explicit Reuse choice may apply a saved result.
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.reuse()
        assertTrue(controller.state.value is AiSummaryState.Ready)
        assertEquals(1, api.requests.size)
        for ((other, language) in listOf(chapter.copy(chapterId = "chapter-5") to "en",
            chapter.copy(bookId = "other-novel") to "en", chapter.copy(text = "Reloaded text") to "en", chapter to "id")) {
            controller.open(other, language)
            assertTrue(controller.state.value is AiSummaryState.Confirm)
        }
        assertEquals(1, api.requests.size)
    }

    @Test
    fun regenerationRequiresNewConfirmationAndKeepsThePreviousResultIfItFails() {
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.generate()
        store.value = store.value.withConfig(AiProviderConfig("new-key", "new-model"))
        api.answer = { throw AiProviderException(AiFailure.RATE_LIMIT) }
        controller.regenerate()
        assertEquals("new-model", (controller.state.value as AiSummaryState.Confirm).plan.config.model)
        assertEquals(1, api.requests.size)
        controller.generate()
        assertEquals(AiFailure.RATE_LIMIT, (controller.state.value as AiSummaryState.Failed).reason)
        assertEquals(2, api.requests.size)
        controller.close()
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.reuse()
        assertEquals("A concise recap.", (controller.state.value as AiSummaryState.Ready).output.text)
    }

    @Test
    fun outdatedSettingsLoadsCannotOpenAnotherChaptersConfirmationOrResurrectAClosedDialog() {
        val pending = CompletableDeferred<AiSettings>()
        val store = Store().apply { loader = { withContext(NonCancellable) { pending.await() } } }
        val controller = AiSummaryController(scope, store, Api())
        controller.open(chapter, "en")
        store.loader = { store.value }
        val next = chapter.copy(chapterId = "chapter-5", title = "Chapter 5")
        controller.open(next, "en")
        pending.complete(store.value)
        assertEquals(next, (controller.state.value as AiSummaryState.Confirm).plan.chapter)
        val late = CompletableDeferred<AiSettings>()
        store.loader = { withContext(NonCancellable) { late.await() } }
        controller.open(chapter, "en")
        controller.close()
        late.complete(store.value)
        assertEquals(AiSummaryState.Closed, controller.state.value)
    }

    @Test
    fun missingCredentialsAndModelsFailBeforeConfirmationAndRetryReloadsSettings() {
        val store = Store().apply { value = AiSettings() }
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        assertEquals(AiFailure.MISSING_KEY, (controller.state.value as AiSummaryState.Failed).reason)
        store.value = AiSettings().withConfig(AiProviderConfig("key", ""))
        controller.retry()
        assertEquals(AiFailure.MISSING_MODEL, (controller.state.value as AiSummaryState.Failed).reason)
        store.value = AiSettings(provider = AiProvider.OPENAI).withConfig(AiProviderConfig("", "local-model", "http://localhost:8080/v1"))
        controller.retry()
        assertEquals("http://localhost:8080/v1/", (controller.state.value as AiSummaryState.Confirm).plan.config.baseUrl)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun loadErrorsAndInvalidChapterContentNeverStartGeneration() {
        val store = Store().apply { loader = { throw IOException("sensitive details") } }
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        assertEquals(AiFailure.LOAD_SETTINGS, (controller.state.value as AiSummaryState.Failed).reason)
        controller.open(chapter.copy(text = ""), "en")
        assertEquals(AiFailure.EMPTY_CHAPTER, (controller.state.value as AiSummaryState.Failed).reason)
        controller.open(chapter.copy(text = "a".repeat(1_000_001)), "en")
        assertEquals(AiFailure.REQUEST_TOO_LARGE, (controller.state.value as AiSummaryState.Failed).reason)
        controller.generate()
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun partialOutputKeepsItsWarningAndEmptyResponsesAreNeverCached() {
        val api = Api().apply { answer = { AiSummaryOutput("Partial recap", truncated = true) } }
        val controller = AiSummaryController(scope, Store(), api)
        controller.open(chapter, "en")
        controller.generate()
        assertTrue((controller.state.value as AiSummaryState.Ready).output.truncated)
        val other = chapter.copy(chapterId = "empty-output")
        api.answer = { AiSummaryOutput(" ") }
        controller.open(other, "en")
        controller.generate()
        assertEquals(AiFailure.EMPTY_RESPONSE, (controller.state.value as AiSummaryState.Failed).reason)
        controller.close()
        controller.open(other, "en")
        assertTrue(controller.state.value is AiSummaryState.Confirm)
    }

    @Test
    fun savedSummaryRegenerationRequiresFreshConfirmationForTheOriginalChapter() {
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.generate()
        controller.close()
        store.value = store.value.withConfig(AiProviderConfig("replacement-key", "replacement-model"))
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.regenerate()
        assertTrue((controller.state.value as AiSummaryState.Confirm).regenerating)
        val plan = (controller.state.value as AiSummaryState.Confirm).plan
        assertEquals(chapter.text, plan.chapter.text)
        assertEquals("replacement-model", plan.config.model)
        assertEquals(1, api.requests.size)
        controller.generate()
        assertEquals(2, api.requests.size)
        assertEquals(chapter.text, api.requests.last().chapter.text)
    }

    @Test
    fun tokenEstimateIncludesPromptAndHandlesNonLatinTextAndSurrogatePairs() {
        val ascii = estimateSummaryTokens("a".repeat(100))
        assertTrue(estimateSummaryTokens("界".repeat(100)) > ascii)
        assertTrue(estimateSummaryTokens("😀".repeat(100)) > estimateSummaryTokens("界".repeat(100)))
        val plan = AiSummaryPlan(chapter, AiProvider.GEMINI, "", AiProviderConfig("key", "model"), "en")
        assertTrue(plan.inputTokens > estimateSummaryTokens(chapter.text))
        assertTrue(plan.copy(chapter = chapter.copy(text = chapter.text.repeat(100))).inputTokens > plan.inputTokens)
    }
}
