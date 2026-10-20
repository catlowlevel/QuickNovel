package com.lagradost.quicknovel.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AiSummaryCacheControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chapter = AiChapter("book", "chapter-4", "Novel", "Chapter 4", "Original chapter text.")

    private class Store : AiSettingsPersistence {
        var loads = 0
        var fail = false
        var value = AiSettings().withConfig(AiProviderConfig("private-key", "chosen-model"))

        override suspend fun load(): AiSettings {
            loads++
            if (fail) throw IOException("Offline key storage")
            return value
        }

        override suspend fun save(settings: AiSettings) {
            error("Must not save credentials")
        }
    }

    private class Api : AiSummaryApi {
        var calls = 0
        var answer: suspend () -> AiSummaryOutput = { AiSummaryOutput("Generated recap") }

        override suspend fun summarize(
            provider: AiProvider,
            config: AiProviderConfig,
            chapter: AiChapter,
            language: String,
        ): AiSummaryOutput {
            calls++
            return answer()
        }
    }

    private fun record() =
        AiCachedSummary(
            chapter.resultKey("en"),
            "en",
            AiProvider.OPENAI,
            "Saved key",
            "saved-model",
            AiSummaryOutput("Saved recap"),
        )

    private suspend inline fun <reified T : AiSummaryState> AiSummaryController.await(): T =
        withTimeout(3000) { state.first { it is T } as T }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun generationSurvivesControllerRestartAndOfflineReuseDoesNotReadCredentialsOrCallTheApi() = runBlocking {
        val directory = temp.newFolder()
        val api = Api()
        val first = AiSummaryController(scope, Store(), api, FileAiSummaryCache(directory))
        first.open(chapter, "en")
        first.await<AiSummaryState.Confirm>()
        first.generate()
        assertFalse(first.await<AiSummaryState.Ready>().cacheWriteFailed)
        first.close()
        val offline = Store().apply { fail = true }
        val reopened = AiSummaryController(scope, offline, api, FileAiSummaryCache(directory))
        reopened.open(chapter, "en")
        assertEquals("Generated recap", reopened.await<AiSummaryState.Saved>().result.output.text)
        assertEquals(0, offline.loads)
        assertEquals(1, api.calls)
        reopened.generate()
        assertTrue(reopened.state.value is AiSummaryState.Saved)
        reopened.reuse()
        assertEquals("Generated recap", reopened.await<AiSummaryState.Ready>().output.text)
        assertEquals(0, offline.loads)
        assertEquals(1, api.calls)
    }

    @Test
    fun cacheMissesStillRequireConfirmationAndNeverStartPaidRequestsAutomatically() = runBlocking {
        val cache = FileAiSummaryCache(temp.newFolder())
        cache.save(record())
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.open(chapter.copy(text = "Revised chapter"), "en")
        controller.await<AiSummaryState.Confirm>()
        controller.close()
        controller.open(chapter, "id")
        controller.await<AiSummaryState.Confirm>()
        assertEquals(0, api.calls)
        assertEquals(2, store.loads)
    }

    @Test
    fun successfulExplicitRegenerationUpdatesTheFileOnlyAfterConfirmation() = runBlocking {
        val directory = temp.newFolder()
        val cache = FileAiSummaryCache(directory)
        cache.save(record())
        val api = Api()
        val store = Store()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.open(chapter, "en")
        controller.await<AiSummaryState.Saved>()
        assertEquals(0, store.loads)
        controller.regenerate()
        assertEquals("chosen-model", controller.await<AiSummaryState.Confirm>().plan.config.model)
        assertEquals(1, store.loads)
        assertEquals(record().output, cache.load(chapter, "en")!!.output)
        assertEquals(0, api.calls)
        controller.generate()
        controller.await<AiSummaryState.Ready>()
        val stored = FileAiSummaryCache(directory).load(chapter, "en")!!
        assertEquals("Generated recap", stored.output.text)
        assertEquals("chosen-model", stored.model)
    }

    @Test
    fun failedAndCancelledRegenerationPreserveThePreviousFile() = runBlocking {
        val cache = FileAiSummaryCache(temp.newFolder())
        cache.save(record())
        val api = Api().apply { answer = { throw AiProviderException(AiFailure.SERVER) } }
        val controller = AiSummaryController(scope, Store(), api, cache)
        controller.regenerate(chapter, "en")
        controller.await<AiSummaryState.Confirm>()
        controller.generate()
        controller.await<AiSummaryState.Failed>()
        assertEquals(record().output, cache.load(chapter, "en")!!.output)
        val late = CompletableDeferred<AiSummaryOutput>()
        api.answer = { withContext(NonCancellable) { late.await() } }
        controller.regenerate(chapter, "en")
        controller.await<AiSummaryState.Confirm>()
        controller.generate()
        controller.close()
        late.complete(AiSummaryOutput("Cancelled replacement"))
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(record().output, cache.load(chapter, "en")!!.output)
    }

    @Test
    fun unwritableCacheStillReturnsThePaidOutputWithAVisibleSaveFailureFlag() = runBlocking {
        val api = Api()
        val controller = AiSummaryController(scope, Store(), api, FileAiSummaryCache(temp.newFile()))
        controller.open(chapter, "en")
        controller.await<AiSummaryState.Confirm>()
        controller.generate()
        val ready = controller.await<AiSummaryState.Ready>()
        assertEquals("Generated recap", ready.output.text)
        assertTrue(ready.cacheWriteFailed)
        assertEquals(1, api.calls)
    }

    @Test
    fun cancellingSavedSummaryChoiceLeavesTheCacheAvailableWithoutApplyingOrRegenerating() = runBlocking {
        val cache = FileAiSummaryCache(temp.newFolder())
        cache.save(record())
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.open(chapter, "en")
        controller.await<AiSummaryState.Saved>()
        controller.close()
        controller.reuse()
        controller.regenerate()
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(record().output, cache.load(chapter, "en")!!.output)
        assertEquals(0, store.loads)
        assertEquals(0, api.calls)
        controller.open(chapter, "en")
        assertEquals("Saved recap", controller.await<AiSummaryState.Saved>().result.output.text)
    }

    @Test
    fun missingCredentialsOnRegenerationKeepTheSavedSummaryReusable() = runBlocking {
        val cache = FileAiSummaryCache(temp.newFolder())
        cache.save(record())
        val store = Store().apply { value = AiSettings() }
        val api = Api()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.open(chapter, "en")
        controller.await<AiSummaryState.Saved>()
        controller.regenerate()
        assertEquals(AiFailure.MISSING_KEY, controller.await<AiSummaryState.Failed>().reason)
        controller.close()
        controller.open(chapter, "en")
        controller.await<AiSummaryState.Saved>()
        controller.reuse()
        assertEquals("Saved recap", controller.await<AiSummaryState.Ready>().output.text)
        assertEquals(0, api.calls)
        assertEquals(record().output, cache.load(chapter, "en")!!.output)
    }

    @Test
    fun aLateCacheLookupCannotReopenAClosedDialogOrLoadCredentials() = runBlocking {
        val result = CompletableDeferred<AiCachedSummary?>()
        val cache =
            object : AiSummaryCache {
                override suspend fun load(chapter: AiChapter, language: String) =
                    withContext(NonCancellable) { result.await() }

                override suspend fun save(result: AiCachedSummary) {
                    error("No generation expected")
                }
            }
        val store = Store()
        val api = Api()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.open(chapter, "en")
        controller.close()
        result.complete(record())
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(0, store.loads)
        assertEquals(0, api.calls)
    }
}
