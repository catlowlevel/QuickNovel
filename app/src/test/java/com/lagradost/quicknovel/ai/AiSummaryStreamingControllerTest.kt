package com.lagradost.quicknovel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiSummaryStreamingControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chapter = AiChapter("book", "chapter", "Novel", "Chapter", "Full original chapter.")
    private val store = object : AiSettingsPersistence {
        override suspend fun load() = AiSettings().withConfig(AiProviderConfig("key", "model"))
        override suspend fun save(settings: AiSettings) = error("No settings writes")
    }
    private class Api : AiSummaryApi {
        val callbacks = mutableListOf<(Long) -> Unit>()
        val replies = mutableListOf<CompletableDeferred<AiSummaryOutput>>()
        override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput =
            error("The controller must request streaming")

        override suspend fun streamSummary(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String,
                                           onProgress: (Long) -> Unit): AiSummaryOutput {
            callbacks += onProgress
            val reply = CompletableDeferred<AiSummaryOutput>()
            replies += reply
            return withContext(NonCancellable) { reply.await() } // Simulate even late transport callbacks.
        }
    }
    private inner class Cache : AiSummaryCache {
        val original = AiCachedSummary(chapter.resultKey("en"), "en", AiProvider.GEMINI, "Saved", "saved-model", AiSummaryOutput("Saved recap"))
        var current = original
        var writes = 0
        override suspend fun load(chapter: AiChapter, language: String) = current
        override suspend fun save(result: AiCachedSummary) { writes++; current = result }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun progressContainsOnlyAMonotonicCountAndCannotReplaceTheSavedResultBeforeCompletion() {
        val api = Api()
        val cache = Cache()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.regenerate(chapter, "en")
        controller.generate()
        assertEquals(0L, (controller.state.value as AiSummaryState.Running).generatedTokens)
        val callback = api.callbacks.single()
        Thread { callback(12); callback(7); callback(-1) }.apply { start(); join() }
        assertEquals(12L, (controller.state.value as AiSummaryState.Running).generatedTokens)
        assertEquals(cache.original, cache.current)
        assertEquals(0, cache.writes)
        api.replies.single().complete(AiSummaryOutput("Complete replacement"))
        assertEquals("Complete replacement", (controller.state.value as AiSummaryState.Ready).output.text)
        assertEquals("Complete replacement", cache.current.output.text)
        assertEquals(1, cache.writes)
        callback(999)
        assertTrue(controller.state.value is AiSummaryState.Ready)
    }

    @Test
    fun cancelledStreamsCannotResurrectTheirProgressOrUpdateAnotherRequestsCount() {
        val api = Api()
        val cache = Cache()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.regenerate(chapter, "en")
        controller.generate()
        val stale = api.callbacks.single()
        stale(20)
        controller.close()
        stale(50)
        assertEquals(AiSummaryState.Closed, controller.state.value)
        controller.regenerate(chapter, "en")
        controller.generate()
        api.callbacks.last()(3)
        stale(900)
        assertEquals(3L, (controller.state.value as AiSummaryState.Running).generatedTokens)
        api.replies.first().complete(AiSummaryOutput("Late stale summary"))
        assertTrue(controller.state.value is AiSummaryState.Running)
        assertEquals(cache.original, cache.current)
        api.replies.last().complete(AiSummaryOutput("New summary"))
        assertEquals("New summary", (controller.state.value as AiSummaryState.Ready).output.text)
        assertEquals(1, cache.writes)
    }

    @Test
    fun openingAnotherChapterOrRegeneratingCannotDiscardTheActivePaidRequest() {
        val api = Api()
        val controller = AiSummaryController(scope, store, api)
        controller.open(chapter, "en")
        controller.generate()
        val running = controller.state.value as AiSummaryState.Running
        val other = chapter.copy(chapterId = "next-chapter", title = "Next chapter")
        controller.open(other, "en")
        controller.regenerate(other, "en")
        controller.generate()
        assertEquals(running, controller.state.value)
        assertEquals(1, api.replies.size)
        api.callbacks.single()(17)
        assertEquals(17L, (controller.state.value as AiSummaryState.Running).generatedTokens)
        api.replies.single().complete(AiSummaryOutput("Original request completed"))
        val ready = controller.state.value as AiSummaryState.Ready
        assertEquals(chapter, ready.plan.chapter)
        assertEquals("Original request completed", ready.output.text)
        controller.open(other, "en")
        assertEquals(other, (controller.state.value as AiSummaryState.Confirm).plan.chapter)
    }

    @Test
    fun failureAfterProgressRetainsThePreviousCacheAndRetryStartsWithAZeroCount() {
        val api = Api()
        val cache = Cache()
        val controller = AiSummaryController(scope, store, api, cache)
        controller.regenerate(chapter, "en")
        controller.generate()
        api.callbacks.single()(30)
        api.replies.single().completeExceptionally(AiProviderException(AiFailure.NETWORK))
        assertEquals(AiFailure.NETWORK, (controller.state.value as AiSummaryState.Failed).reason)
        assertEquals(cache.original, cache.current)
        assertEquals(0, cache.writes)
        controller.retry()
        controller.generate()
        assertEquals(0L, (controller.state.value as AiSummaryState.Running).generatedTokens)
        controller.close()
        api.replies.last().completeExceptionally(CancellationException())
        controller.open(chapter, "en")
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.reuse()
        assertEquals(cache.original.output, (controller.state.value as AiSummaryState.Ready).output)
    }
}
