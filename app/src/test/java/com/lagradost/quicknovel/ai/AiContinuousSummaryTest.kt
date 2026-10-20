package com.lagradost.quicknovel.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiContinuousSummaryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chapters = (0..3).map { AiChapter("book", "chapter-$it", "Novel", "Chapter $it", "Original text $it.") }
    private class Store : AiSettingsPersistence {
        var loads = 0
        var value = AiSettings().withConfig(AiProviderConfig("approved-key", "approved-model"))
        override suspend fun load(): AiSettings { loads++; return value }
        override suspend fun save(settings: AiSettings) = error("No settings writes")
    }
    private class Api : AiSummaryApi {
        val calls = mutableListOf<AiSummaryPlan>()
        val callbacks = mutableListOf<(Long) -> Unit>()
        var answer: suspend (AiChapter) -> AiSummaryOutput = { AiSummaryOutput("Recap for ${it.chapterId}") }
        override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput =
            error("Use streaming")

        override suspend fun streamSummary(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String,
                                           onProgress: (Long) -> Unit): AiSummaryOutput {
            calls += AiSummaryPlan(chapter, provider, "", config, language)
            callbacks += onProgress
            onProgress(4)
            return answer(chapter)
        }
    }
    private class Cache : AiSummaryCache {
        val records = mutableMapOf<String, AiCachedSummary>()
        var failWrites = false
        override suspend fun load(chapter: AiChapter, language: String) = records[chapter.resultKey(language)]
        override suspend fun save(result: AiCachedSummary) {
            if (failWrites) throw IOException("Unavailable disk")
            records[result.sourceKey] = result
        }
        fun seed(chapter: AiChapter, truncated: Boolean = false) {
            records[chapter.resultKey("en")] = AiCachedSummary(chapter.resultKey("en"), "en", AiProvider.OPENAI,
                "Old profile", "old-model", AiSummaryOutput("Saved ${chapter.chapterId}", truncated))
        }
    }
    private open class Source(private val chapters: List<AiChapter>, loaded: Set<Int>) : AiSummaryChapterSource {
        override val changes = MutableStateFlow(0L)
        val loaded = loaded.toMutableSet()
        var nextChecks = 0
        var changed = false
        override suspend fun next(after: AiChapter): AiNextSummaryChapter {
            nextChecks++
            val index = chapters.indexOf(after)
            if (changed || index < 0) return AiNextSummaryChapter.SourceChanged
            val next = index + 1
            return when {
                next == chapters.size -> AiNextSummaryChapter.End
                next in loaded -> AiNextSummaryChapter.Loaded(chapters[next])
                else -> AiNextSummaryChapter.Waiting(chapters[next].title)
            }
        }
        override suspend fun loadedAfter(after: AiChapter): List<AiChapter> {
            val index = chapters.indexOf(after)
            if (changed || index < 0) throw AiProviderException(AiFailure.SOURCE_CHANGED)
            return chapters.drop(index + 1).takeWhile { chapters.indexOf(it) in loaded }
        }
        fun load(index: Int) { loaded += index; changes.value++ }
    }
    private fun controller(api: Api, source: Source, cache: Cache = Cache(), store: Store = Store()) =
        AiSummaryController(scope, store, api, cache).apply { attachChapterSource(source) }

    private fun AiSummaryController.start(chapter: AiChapter = chapters[0]) {
        open(chapter, "en")
        setContinuous(true)
        generate()
    }
    private fun AiSummaryController.ready(): AiSummaryState.Ready = state.value as AiSummaryState.Ready
    private fun AiSummaryController.accept() = acknowledge(ready())

    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun oneChapterRemainsTheDefaultAndSelectingContinuousDoesNotSendRequestsBeforeConfirmation() {
        val api = Api()
        val source = Source(chapters, setOf(0, 1, 2, 3))
        val controller = controller(api, source)
        controller.open(chapters[0], "en")
        val initial = controller.state.value as AiSummaryState.Confirm
        assertFalse(initial.continuous)
        assertEquals(4, initial.loadedCount)
        assertEquals(chapters.sumOf { initial.plan.copy(chapter = it).inputTokens }, initial.knownInputTokens)
        controller.setContinuous(true)
        assertTrue((controller.state.value as AiSummaryState.Confirm).continuous)
        assertTrue(api.calls.isEmpty())
        controller.setContinuous(false)
        controller.generate()
        assertFalse(controller.ready().continuous)
        controller.accept()
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(listOf(chapters[0]), api.calls.map { it.chapter })
        assertEquals(0, source.nextChecks)
    }

    @Test
    fun continuousProcessingIsSequentialReusesSavedResultsAndPinsTheApprovedConfiguration() {
        val api = Api()
        val source = Source(chapters.take(3), setOf(0, 1, 2))
        val cache = Cache().apply { seed(chapters[1]) }
        val store = Store()
        val controller = controller(api, source, cache, store)
        controller.start()
        val first = controller.ready()
        assertTrue(first.continuous)
        assertEquals(0, source.nextChecks) // A background/rotated reader has not acknowledged the result yet.
        controller.open(chapters[2], "id")
        assertSame(first, controller.state.value)
        store.value = AiSettings(provider = AiProvider.CLAUDE).withConfig(AiProviderConfig("new-key", "new-model"))
        controller.acknowledge(first)
        val cached = controller.ready()
        assertTrue(cached.reused)
        assertEquals("Saved chapter-1", cached.output.text)
        controller.acknowledge(first) // A delayed acknowledgement cannot skip a different result.
        assertSame(cached, controller.state.value)
        assertEquals(1, api.calls.size)
        controller.accept()
        assertEquals(chapters[2], controller.ready().plan.chapter)
        assertEquals(2, controller.ready().completedChapters)
        controller.accept()
        assertEquals(AiSummaryState.Finished(3, AiSummaryFinishReason.END), controller.state.value)
        assertEquals(listOf(chapters[0], chapters[2]), api.calls.map { it.chapter })
        assertTrue(api.calls.all { it.provider == AiProvider.GEMINI && it.config.apiKey == "approved-key" &&
            it.config.model == "approved-model" && it.language == "en" })
        assertEquals(1, store.loads)
        controller.close()
        controller.open(chapters[1], "en")
        assertFalse((controller.state.value as AiSummaryState.Saved).result.continuous)
        controller.reuse()
        controller.accept()
        assertEquals(AiSummaryState.Closed, controller.state.value)
    }

    @Test
    fun waitsAtUnloadedGapsResumesOnLoadAndNeverProcessesEarlierChapters() {
        val api = Api()
        val source = Source(chapters, setOf(0, 1, 3))
        val controller = controller(api, source)
        controller.start(chapters[1])
        controller.accept()
        val waiting = controller.state.value as AiSummaryState.Waiting
        assertEquals(chapters[2].title, waiting.nextTitle)
        assertEquals(1, waiting.completedChapters)
        controller.open(chapters[3], "en")
        assertEquals(waiting, controller.state.value)
        source.load(3) // An unrelated signal cannot skip the missing chapter or issue a request.
        assertTrue(controller.state.value is AiSummaryState.Waiting)
        assertEquals(1, api.calls.size)
        source.load(2)
        assertEquals(chapters[2], controller.ready().plan.chapter)
        controller.accept()
        assertEquals(chapters[3], controller.ready().plan.chapter)
        controller.accept()
        assertEquals(listOf(chapters[1], chapters[2], chapters[3]), api.calls.map { it.chapter })
        assertEquals(AiSummaryState.Finished(3, AiSummaryFinishReason.END), controller.state.value)
    }

    @Test
    fun stoppingWhileWaitingDisarmsFutureLoadSignalsAndPreservesCompletedSummaries() {
        val api = Api()
        val source = Source(chapters, setOf(0))
        val cache = Cache()
        val controller = controller(api, source, cache)
        controller.start()
        controller.accept()
        assertTrue(controller.state.value is AiSummaryState.Waiting)
        controller.close()
        source.load(1)
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(1, api.calls.size)
        assertNotNull(cache.loadRecord(chapters[0]))
        controller.open(chapters[0], "en")
        controller.reuse()
        assertFalse(controller.ready().continuous)
        controller.accept()
        assertEquals(AiSummaryState.Closed, controller.state.value)
    }

    private fun Cache.loadRecord(chapter: AiChapter) = records[chapter.resultKey("en")]

    @Test
    fun stopDuringGenerationCancelsTransportAndRejectsLateCountsAndOutput() {
        val pending = CompletableDeferred<AiSummaryOutput>()
        var cancelled = false
        val api = Api().apply { answer = { try { pending.await() } finally { cancelled = true } } }
        val cache = Cache()
        val source = Source(chapters, setOf(0, 1, 2, 3))
        val controller = controller(api, source, cache)
        controller.start()
        assertTrue(controller.state.value is AiSummaryState.Running)
        controller.close()
        assertTrue(cancelled)
        api.callbacks.single()(999)
        pending.complete(AiSummaryOutput("Cancelled output"))
        source.load(1)
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertTrue(cache.records.isEmpty())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun providerFailureStopsTheQueueAndRetryRequiresFreshContinuousConfirmation() {
        val api = Api().apply { answer = {
            if (it == chapters[1]) throw AiProviderException(AiFailure.RATE_LIMIT)
            AiSummaryOutput("Good output")
        } }
        val cache = Cache()
        val source = Source(chapters.take(3), setOf(0, 1, 2))
        val controller = controller(api, source, cache)
        controller.start()
        controller.accept()
        val failed = controller.state.value as AiSummaryState.Failed
        assertEquals(AiFailure.RATE_LIMIT, failed.reason)
        assertTrue(failed.continuous)
        assertEquals(chapters[1], failed.chapter)
        assertNotNull(cache.loadRecord(chapters[0]))
        assertEquals(2, api.calls.size)
        api.answer = { AiSummaryOutput("Retry output") }
        controller.retry()
        assertTrue((controller.state.value as AiSummaryState.Confirm).continuous)
        assertEquals(2, api.calls.size)
        controller.generate()
        controller.accept()
        controller.accept()
        assertEquals(AiSummaryState.Finished(2, AiSummaryFinishReason.END), controller.state.value)
        assertEquals(listOf(chapters[0], chapters[1], chapters[1], chapters[2]), api.calls.map { it.chapter })
    }

    @Test
    fun unsavedOrIncompleteOutputStaysAvailableButStopsFurtherPaidRequests() {
        for (incomplete in listOf(false, true)) {
            val api = Api().apply { answer = { AiSummaryOutput("Paid output", truncated = incomplete) } }
            val cache = Cache().apply { failWrites = !incomplete }
            val controller = controller(api, Source(chapters, setOf(0, 1, 2, 3)), cache)
            controller.start()
            assertEquals("Paid output", controller.ready().output.text)
            controller.accept()
            assertEquals(AiSummaryState.Finished(1,
                if (incomplete) AiSummaryFinishReason.INCOMPLETE else AiSummaryFinishReason.CACHE_WRITE_FAILED), controller.state.value)
            assertEquals(1, api.calls.size)
            controller.close()
            controller.open(chapters[0], "en")
            controller.reuse()
            assertEquals("Paid output", controller.ready().output.text)
            assertFalse(controller.ready().continuous)
            controller.close()
        }
    }

    @Test
    fun continuousReuseOfTheCurrentSavedSummaryRequiresConfirmationAndDoesNotRegenerateIt() {
        val api = Api()
        val store = Store()
        val cache = Cache().apply { seed(chapters[0]) }
        val controller = controller(api, Source(chapters.take(2), setOf(0, 1)), cache, store)
        controller.open(chapters[0], "en")
        assertEquals(0, store.loads)
        controller.setContinuous(true)
        controller.reuse()
        val confirmed = controller.state.value as AiSummaryState.Confirm
        assertTrue(confirmed.continuous)
        assertNotNull(confirmed.reusedFirst)
        assertEquals(confirmed.plan.copy(chapter = chapters[1]).inputTokens, confirmed.knownInputTokens)
        assertTrue(api.calls.isEmpty())
        controller.setContinuous(false)
        assertTrue(controller.state.value is AiSummaryState.Saved)
        controller.setContinuous(true)
        controller.reuse()
        controller.generate()
        assertTrue(controller.ready().reused)
        controller.accept()
        assertEquals(chapters[1], controller.ready().plan.chapter)
        controller.accept()
        assertEquals(listOf(chapters[1]), api.calls.map { it.chapter })
    }

    @Test
    fun explicitContinuousRegenerationReplacesOnlyTheFirstSavedResultAndReusesTheRest() {
        val api = Api()
        val cache = Cache().apply { seed(chapters[0]); seed(chapters[1]) }
        val controller = controller(api, Source(chapters.take(2), setOf(0, 1)), cache)
        controller.open(chapters[0], "en")
        controller.setContinuous(true)
        controller.regenerate()
        assertTrue((controller.state.value as AiSummaryState.Confirm).regenerating)
        assertTrue((controller.state.value as AiSummaryState.Confirm).continuous)
        controller.generate()
        assertFalse(controller.ready().reused)
        controller.accept()
        assertTrue(controller.ready().reused)
        controller.accept()
        assertEquals(listOf(chapters[0]), api.calls.map { it.chapter })
    }

    @Test
    fun retryAfterSettingsFailurePreservesTheExplicitChoiceToReuseTheFirstSummary() {
        val api = Api()
        var settingsAvailable = false
        val store = object : AiSettingsPersistence {
            override suspend fun load(): AiSettings {
                if (!settingsAvailable) throw IOException("Cannot read settings yet")
                return AiSettings().withConfig(AiProviderConfig("key", "model"))
            }
            override suspend fun save(settings: AiSettings) = error("No settings writes")
        }
        val cache = Cache().apply { seed(chapters[0]) }
        val controller = AiSummaryController(scope, store, api, cache).apply {
            attachChapterSource(Source(chapters.take(2), setOf(0, 1)))
        }
        controller.open(chapters[0], "en")
        controller.setContinuous(true)
        controller.reuse()
        val failed = controller.state.value as AiSummaryState.Failed
        assertEquals(AiFailure.LOAD_SETTINGS, failed.reason)
        assertNotNull(failed.reusedFirst)
        settingsAvailable = true
        controller.retry()
        val confirmed = controller.state.value as AiSummaryState.Confirm
        assertNotNull(confirmed.reusedFirst)
        assertFalse(confirmed.regenerating)
        controller.generate()
        assertTrue(controller.ready().reused)
        controller.accept()
        controller.accept()
        assertEquals(listOf(chapters[1]), api.calls.map { it.chapter })
    }

    @Test
    fun aLoadSignalDuringTheNextChapterQueryCannotBeMissed() {
        val api = Api()
        val source = object : Source(chapters.take(2), setOf(0)) {
            var race = true
            override suspend fun next(after: AiChapter): AiNextSummaryChapter {
                if (race) {
                    race = false
                    load(1)
                    return AiNextSummaryChapter.Waiting(chapters[1].title)
                }
                return super.next(after)
            }
        }
        val controller = controller(api, source)
        controller.start()
        controller.accept()
        assertEquals(chapters[1], controller.ready().plan.chapter)
        controller.accept()
        assertEquals(AiSummaryState.Finished(2, AiSummaryFinishReason.END), controller.state.value)
    }

    @Test
    fun lateSourceQueriesAndBookChangesCannotRestartAStoppedQueueOrSendAnotherBooksText() {
        val api = Api()
        val late = CompletableDeferred<AiNextSummaryChapter>()
        val source = object : Source(chapters, setOf(0)) {
            override suspend fun next(after: AiChapter) = withContext(NonCancellable) { late.await() }
        }
        val controller = controller(api, source)
        controller.start()
        controller.accept()
        controller.close()
        late.complete(AiNextSummaryChapter.Waiting(chapters[1].title))
        assertEquals(AiSummaryState.Closed, controller.state.value)
        assertEquals(1, api.calls.size)
        val changed = Source(chapters, setOf(0, 1))
        val otherController = controller(api, changed)
        otherController.start()
        changed.changed = true
        otherController.accept()
        assertEquals(AiFailure.SOURCE_CHANGED, (otherController.state.value as AiSummaryState.Failed).reason)
        assertEquals(2, api.calls.size)
    }
}
