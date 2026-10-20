package com.lagradost.quicknovel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiSummaryKey(val provider: AiProvider, val id: String, val name: String, val model: String)

sealed interface AiSummaryState {
    data object Closed : AiSummaryState
    data class Preparing(val chapter: AiChapter) : AiSummaryState
    data class Saved(val result: Ready, val continuous: Boolean = false) : AiSummaryState
    data class Confirm(
        val plan: AiSummaryPlan,
        val regenerating: Boolean = false,
        val continuous: Boolean = false,
        val reusedFirst: Ready? = null,
        val following: List<AiChapter> = emptyList(),
    ) : AiSummaryState {
        val loadedCount: Int get() = 1 + following.size
        val knownInputTokens: Long get() = (if (reusedFirst == null) plan.inputTokens else 0) +
            following.sumOf { plan.copy(chapter = it).inputTokens }
    }
    data class Running(val plan: AiSummaryPlan, val generatedTokens: Long = 0,
                       val continuous: Boolean = false, val completedChapters: Int = 0) : AiSummaryState
    data class Ready(val plan: AiSummaryPlan, val output: AiSummaryOutput, val cacheWriteFailed: Boolean = false,
                     val continuous: Boolean = false, val completedChapters: Int = 0, val reused: Boolean = false) : AiSummaryState
    data class Waiting(val chapter: AiChapter, val completedChapters: Int, val nextTitle: String) : AiSummaryState
    data class Finished(val completedChapters: Int, val reason: AiSummaryFinishReason) : AiSummaryState
    data class Failed(val chapter: AiChapter, val language: String, val reason: AiFailure,
                      val continuous: Boolean = false, val reusedFirst: Ready? = null) : AiSummaryState
}

/** Paid work starts only after confirmation; continuous confirmation also covers later loaded chapters. */
class AiSummaryController(
    private val scope: CoroutineScope,
    private val store: AiSettingsPersistence,
    private val api: AiSummaryApi,
    private val cache: AiSummaryCache? = null,
) {
    private val mutableState = MutableStateFlow<AiSummaryState>(AiSummaryState.Closed)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var pendingAcknowledgement: CompletableDeferred<Unit>? = null
    @Volatile
    private var revision = 0L
    private val results = linkedMapOf<String, AiSummaryState.Ready>()
    private var savedSettings: AiSettings? = null
    private var keySelection: Pair<AiProvider, String>? = null
    val savedKeys: List<AiSummaryKey> get() = AiProvider.entries.flatMap { provider ->
        savedSettings?.profiles?.get(provider).orEmpty().map { AiSummaryKey(provider, it.id, it.name, it.config.model) }
    }
    val selectedKey: AiSummaryKey? get() {
        val settings = savedSettings ?: return null
        val (provider, id) = keySelection ?: (settings.provider to settings.profile().id)
        return savedKeys.firstOrNull { it.provider == provider && it.id == id }
    }
    private var chapterSource: AiSummaryChapterSource? = null
    val canContinue: Boolean get() = chapterSource != null

    fun attachChapterSource(source: AiSummaryChapterSource) {
        chapterSource = source
    }

    fun isProcessing(): Boolean = when (val current = state.value) {
        is AiSummaryState.Running, is AiSummaryState.Waiting -> true
        is AiSummaryState.Ready -> current.continuous
        else -> false
    }

    // Assign before starting: a synchronous completion/acknowledgement cannot overwrite a newer job.
    private fun launchWork(block: suspend () -> Unit) {
        val work = scope.launch(start = CoroutineStart.LAZY) { block() }
        job = work
        work.start()
    }

    fun open(chapter: AiChapter, language: String) = prepare(chapter, language, reuse = true)

    fun regenerate(chapter: AiChapter, language: String) = prepare(chapter, language, reuse = false)

    fun selectKey(provider: AiProvider, id: String) {
        val current = state.value
        if (current !is AiSummaryState.Confirm && current !is AiSummaryState.Failed) return
        val profile = savedSettings?.profiles?.get(provider)?.firstOrNull { it.id == id } ?: return
        keySelection = provider to id
        if (current is AiSummaryState.Confirm) {
            try {
                val config = validatedAiConfig(provider, profile.config, requireModel = true)
                mutableState.value = current.copy(plan = current.plan.copy(
                    provider = provider, profileName = profile.name, config = config))
            } catch (error: AiProviderException) {
                mutableState.value = AiSummaryState.Failed(current.plan.chapter, current.plan.language,
                    error.failure, current.continuous, current.reusedFirst)
            }
        } else retry()
    }

    private fun prepare(chapter: AiChapter, language: String, reuse: Boolean,
                        continuous: Boolean = false, reusedFirst: AiSummaryState.Ready? = null) {
        if (isProcessing()) return
        close()
        val token = revision
        mutableState.value = AiSummaryState.Preparing(chapter)
        launchWork {
            try {
                validateSummaryChapter(chapter)
                if (reuse) {
                    val saved = findSaved(chapter, language)
                    if (revision != token) return@launchWork
                    if (saved != null) {
                        remember(saved)
                        mutableState.value = AiSummaryState.Saved(saved)
                        return@launchWork
                    }
                }
                val settings = try { store.load().normalized() } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { throw AiProviderException(AiFailure.LOAD_SETTINGS) }
                if (revision != token) return@launchWork
                savedSettings = settings
                val (provider, id) = keySelection ?: (settings.provider to settings.profile().id)
                val profile = if (keySelection == null) settings.profile() else
                    settings.profiles[provider]?.firstOrNull { it.id == id }
                        ?: throw AiProviderException(AiFailure.MISSING_KEY)
                val config = validatedAiConfig(provider, profile.config, requireModel = true)
                val following = chapterSource?.loadedAfter(chapter).orEmpty()
                if (revision == token) mutableState.value = AiSummaryState.Confirm(
                    AiSummaryPlan(chapter, provider, profile.name, config, language),
                    regenerating = !reuse && reusedFirst == null, continuous = continuous,
                    reusedFirst = reusedFirst, following = following)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == token) mutableState.value = AiSummaryState.Failed(chapter, language,
                    (e as? AiProviderException)?.failure ?: AiFailure.INVALID_RESPONSE, continuous, reusedFirst)
            }
        }
    }

    fun setContinuous(enabled: Boolean) {
        if (enabled && !canContinue) return
        mutableState.update { current ->
            when (current) {
                is AiSummaryState.Saved -> current.copy(continuous = enabled)
                is AiSummaryState.Confirm -> if (!enabled && current.reusedFirst != null) {
                    AiSummaryState.Saved(current.reusedFirst)
                } else current.copy(continuous = enabled)
                else -> current
            }
        }
    }

    fun generate() {
        val confirmed = state.value as? AiSummaryState.Confirm ?: return
        val source = if (confirmed.continuous) chapterSource ?: return else null
        val token = ++revision
        mutableState.value = AiSummaryState.Running(confirmed.plan, continuous = source != null)
        launchWork {
            var plan = confirmed.plan
            var completed = 0
            var first = true
            val seen = hashSetOf<String>()
            try {
                while (revision == token) {
                    if (!seen.add(plan.chapter.chapterId)) throw AiProviderException(AiFailure.SOURCE_CHANGED)
                    validateSummaryChapter(plan.chapter)
                    val saved = if (first) confirmed.reusedFirst else findSaved(plan.chapter, plan.language)
                    if (revision != token) return@launchWork
                    val result = saved ?: requestSummary(plan, token, source != null, completed)
                    if (revision != token) return@launchWork
                    val ready = result.copy(continuous = source != null, completedChapters = completed, reused = saved != null)
                    remember(ready)
                    val acknowledged = CompletableDeferred<Unit>()
                    pendingAcknowledgement = acknowledged
                    mutableState.value = ready
                    if (source == null) return@launchWork
                    // Keep every result in state until the reader applies it, including across rotation/backgrounding.
                    acknowledged.await()
                    if (revision != token) return@launchWork
                    pendingAcknowledgement = null
                    completed++
                    if (ready.cacheWriteFailed || ready.output.truncated) {
                        mutableState.value = AiSummaryState.Finished(completed,
                            if (ready.cacheWriteFailed) AiSummaryFinishReason.CACHE_WRITE_FAILED else AiSummaryFinishReason.INCOMPLETE)
                        return@launchWork
                    }
                    var next: AiChapter? = null
                    while (revision == token && next == null) {
                        val before = source.changes.value
                        val available = source.next(plan.chapter)
                        if (revision != token) return@launchWork
                        when (available) {
                            is AiNextSummaryChapter.Loaded -> {
                                if (available.chapter.bookId != plan.chapter.bookId || available.chapter.chapterId == plan.chapter.chapterId) {
                                    throw AiProviderException(AiFailure.SOURCE_CHANGED)
                                }
                                next = available.chapter
                            }
                            is AiNextSummaryChapter.Waiting -> {
                                mutableState.value = AiSummaryState.Waiting(plan.chapter, completed, available.title)
                                source.changes.first { it != before }
                            }
                            AiNextSummaryChapter.End -> {
                                if (revision == token) mutableState.value = AiSummaryState.Finished(completed, AiSummaryFinishReason.END)
                                return@launchWork
                            }
                            AiNextSummaryChapter.SourceChanged -> throw AiProviderException(AiFailure.SOURCE_CHANGED)
                        }
                    }
                    if (revision != token) return@launchWork
                    plan = confirmed.plan.copy(chapter = next ?: return@launchWork)
                    first = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == token) mutableState.value = AiSummaryState.Failed(plan.chapter, plan.language,
                    (e as? AiProviderException)?.failure ?: AiFailure.NETWORK, source != null)
            }
        }
    }

    private suspend fun requestSummary(plan: AiSummaryPlan, token: Long, continuous: Boolean, completed: Int): AiSummaryState.Ready {
        if (revision != token) throw CancellationException()
        mutableState.value = AiSummaryState.Running(plan, continuous = continuous, completedChapters = completed)
        val output = api.streamSummary(plan.provider, plan.config, plan.chapter, plan.language) { count ->
            mutableState.update { current ->
                if (revision == token && current is AiSummaryState.Running && current.plan === plan) {
                    current.copy(generatedTokens = maxOf(current.generatedTokens, count))
                } else current
            }
        }
        if (output.text.isBlank()) throw AiProviderException(AiFailure.EMPTY_RESPONSE)
        if (revision != token) throw CancellationException()
        val cacheWriteFailed = if (cache == null) false else try {
            cache.save(AiCachedSummary(plan.chapter.resultKey(plan.language), plan.language,
                plan.provider, plan.profileName, plan.config.model, output))
            false
        } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
        return AiSummaryState.Ready(plan, output, cacheWriteFailed)
    }

    private suspend fun findSaved(chapter: AiChapter, language: String): AiSummaryState.Ready? {
        results[chapter.resultKey(language)]?.let { return it }
        val cached = try { cache?.load(chapter, language) } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null } ?: return null
        val ready = AiSummaryState.Ready(AiSummaryPlan(chapter, cached.provider, cached.profileName,
            AiProviderConfig(model = cached.model), language), cached.output)
        return ready
    }

    private fun remember(ready: AiSummaryState.Ready) {
        results[ready.plan.chapter.resultKey(ready.plan.language)] = ready.copy(continuous = false, completedChapters = 0, reused = false)
        if (results.size > 16) results.remove(results.keys.first())
    }

    fun acknowledge(ready: AiSummaryState.Ready) {
        if (state.value !== ready) return
        if (ready.continuous) pendingAcknowledgement?.complete(Unit) else close()
    }

    fun reuse() {
        val saved = state.value as? AiSummaryState.Saved ?: return
        if (saved.continuous) prepare(saved.result.plan.chapter, saved.result.plan.language,
            reuse = false, continuous = true, reusedFirst = saved.result)
        else mutableState.value = saved.result
    }

    fun regenerate() {
        val current = state.value
        val ready = when (current) {
            is AiSummaryState.Saved -> current.result
            is AiSummaryState.Ready -> current
            else -> return
        }
        prepare(ready.plan.chapter, ready.plan.language, reuse = false,
            continuous = (current as? AiSummaryState.Saved)?.continuous == true)
    }

    fun retry() {
        val failed = state.value as? AiSummaryState.Failed ?: return
        prepare(failed.chapter, failed.language, reuse = false, continuous = failed.continuous, reusedFirst = failed.reusedFirst)
    }

    fun close() {
        revision++
        job?.cancel()
        job = null
        pendingAcknowledgement?.cancel()
        pendingAcknowledgement = null
        mutableState.value = AiSummaryState.Closed
    }
}
