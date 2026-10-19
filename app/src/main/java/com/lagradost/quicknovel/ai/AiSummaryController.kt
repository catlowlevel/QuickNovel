package com.lagradost.quicknovel.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AiSummaryState {
    data object Closed : AiSummaryState
    data class Preparing(val chapter: AiChapter) : AiSummaryState
    data class Saved(val result: Ready) : AiSummaryState
    data class Confirm(val plan: AiSummaryPlan, val regenerating: Boolean = false) : AiSummaryState
    data class Running(val plan: AiSummaryPlan) : AiSummaryState
    data class Ready(val plan: AiSummaryPlan, val output: AiSummaryOutput, val cacheWriteFailed: Boolean = false) : AiSummaryState
    data class Failed(val chapter: AiChapter, val language: String, val reason: AiFailure) : AiSummaryState
}

/** One explicit confirmation per paid request; saved results can be reused without credentials. */
class AiSummaryController(
    private val scope: CoroutineScope,
    private val store: AiSettingsPersistence,
    private val api: AiSummaryApi,
    private val cache: AiSummaryCache? = null,
) {
    private val mutableState = MutableStateFlow<AiSummaryState>(AiSummaryState.Closed)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private val results = linkedMapOf<String, AiSummaryState.Ready>()

    fun open(chapter: AiChapter, language: String) = prepare(chapter, language, reuse = true)

    fun regenerate(chapter: AiChapter, language: String) = prepare(chapter, language, reuse = false)

    private fun prepare(chapter: AiChapter, language: String, reuse: Boolean) {
        close()
        val token = revision
        if (reuse) results[chapter.resultKey(language)]?.let { mutableState.value = AiSummaryState.Saved(it); return }
        mutableState.value = AiSummaryState.Preparing(chapter)
        job = scope.launch {
            try {
                validateSummaryChapter(chapter)
                if (reuse && cache != null) {
                    val cached = try { cache.load(chapter, language) } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { null }
                    if (revision != token) return@launch
                    if (cached != null) {
                        val ready = AiSummaryState.Ready(AiSummaryPlan(chapter, cached.provider, cached.profileName,
                            AiProviderConfig(model = cached.model), language), cached.output)
                        remember(ready)
                        mutableState.value = AiSummaryState.Saved(ready)
                        return@launch
                    }
                }
                if (revision != token) return@launch
                mutableState.value = AiSummaryState.Preparing(chapter)
                val settings = try { store.load().normalized() } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { throw AiProviderException(AiFailure.LOAD_SETTINGS) }
                val config = validatedAiConfig(settings.provider, settings.config(), requireModel = true)
                if (revision == token) mutableState.value = AiSummaryState.Confirm(AiSummaryPlan(
                    chapter, settings.provider, settings.profile().name, config, language), regenerating = !reuse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == token) mutableState.value = AiSummaryState.Failed(chapter, language,
                    (e as? AiProviderException)?.failure ?: AiFailure.INVALID_RESPONSE)
            }
        }
    }

    fun generate() {
        val plan = (state.value as? AiSummaryState.Confirm)?.plan ?: return
        val token = ++revision
        mutableState.value = AiSummaryState.Running(plan)
        job = scope.launch {
            try {
                val output = api.summarize(plan.provider, plan.config, plan.chapter, plan.language)
                if (output.text.isBlank()) throw AiProviderException(AiFailure.EMPTY_RESPONSE)
                if (revision == token) {
                    val cacheWriteFailed = if (cache == null) false else try {
                        cache.save(AiCachedSummary(plan.chapter.resultKey(plan.language), plan.language,
                            plan.provider, plan.profileName, plan.config.model, output))
                        false
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
                    if (revision == token) {
                        val ready = AiSummaryState.Ready(plan, output, cacheWriteFailed)
                        remember(ready)
                        mutableState.value = ready
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (revision == token) mutableState.value = AiSummaryState.Failed(plan.chapter, plan.language,
                    (e as? AiProviderException)?.failure ?: AiFailure.NETWORK)
            }
        }
    }

    private fun remember(ready: AiSummaryState.Ready) {
        results[ready.plan.chapter.resultKey(ready.plan.language)] = ready
        if (results.size > 16) results.remove(results.keys.first())
    }

    fun reuse() {
        val saved = state.value as? AiSummaryState.Saved ?: return
        mutableState.value = saved.result
    }

    fun regenerate() {
        val ready = when (val current = state.value) {
            is AiSummaryState.Saved -> current.result
            is AiSummaryState.Ready -> current
            else -> return
        }
        prepare(ready.plan.chapter, ready.plan.language, reuse = false)
    }

    fun retry() {
        val failed = state.value as? AiSummaryState.Failed ?: return
        prepare(failed.chapter, failed.language, reuse = false)
    }

    fun close() {
        revision++
        job?.cancel()
        job = null
        mutableState.value = AiSummaryState.Closed
    }
}
