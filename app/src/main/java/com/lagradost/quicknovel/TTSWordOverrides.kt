package com.lagradost.quicknovel

import java.util.UUID
import java.util.regex.Matcher

internal data class TTSWordOverride(
    val id: String = UUID.randomUUID().toString(),
    val find: String = "",
    val replacement: String = "",
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
) {
    enum class Error { EmptyFind, InvalidRegex, InvalidReplacement }

    fun validationError(): Error? {
        if (find.isEmpty()) return Error.EmptyFind
        val pattern = try {
            compile()
        } catch (_: IllegalArgumentException) {
            return Error.InvalidRegex
        }
        return try {
            if (isRegex) pattern.validateReplacement(replacement)
            null
        } catch (_: IllegalArgumentException) {
            Error.InvalidReplacement
        } catch (_: IndexOutOfBoundsException) {
            Error.InvalidReplacement
        }
    }

    fun compile(): Regex {
        require(find.isNotEmpty())
        return Regex(
            if (isRegex) find else Regex.escape(find),
            if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
        )
    }
}

internal data class TTSWordOverrides(val rules: List<TTSWordOverride> = emptyList())

/** Replace an existing rule in place, or append a new draft, without changing saved rules. */
internal fun List<TTSWordOverride>.withRule(rule: TTSWordOverride): List<TTSWordOverride> =
    if (any { it.id == rule.id }) map { if (it.id == rule.id) rule else it } else this + rule

private fun Regex.validateReplacement(replacement: String) {
    // A leading empty alternative guarantees a match while preserving the original
    // capture numbers/names. Validate even when the user's pattern never matches test text.
    Regex("(?:)|$pattern", options).replace("", replacement)
}

internal data class TTSOverrideMatch(val start: Int, val end: Int)
internal data class TTSOverridePreview(val text: String, val matches: List<TTSOverrideMatch>, val matchCount: Int)

/** Compile once per rule edit; preserve original sentence positions and apply rules in list order. */
internal class TTSOverrideEngine(rules: List<TTSWordOverride>) {
    private data class Replacement(val pattern: Regex, val text: String, val isRegex: Boolean)

    private val replacements = rules.filter { it.enabled }.mapNotNull { rule ->
        try {
            val pattern = rule.compile()
            if (rule.isRegex) pattern.validateReplacement(rule.replacement)
            Replacement(pattern, rule.replacement, rule.isRegex)
        } catch (_: IllegalArgumentException) {
            // A damaged/older saved rule must not stop the rest of the narration.
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    val hasRules: Boolean get() = replacements.isNotEmpty()

    fun replace(text: String): String = replacements.fold(text) { result, replacement ->
        with(replacement) {
            if (isRegex) pattern.replace(result, this.text) else pattern.replace(result) { this.text }
        }
    }

    /** Trace actual ordered replacements back to the user's original test text. */
    fun preview(text: String): TTSOverridePreview {
        if (!hasRules) return TTSOverridePreview(text, emptyList(), 0)
        var current = text
        var sources: List<TTSOverrideMatch> = text.indices.map { TTSOverrideMatch(it, it + 1) }
        val matched = mutableListOf<TTSOverrideMatch>()
        var count = 0
        for (replacement in replacements) {
            val matcher = replacement.pattern.toPattern().matcher(current)
            val result = StringBuffer()
            val nextSources = mutableListOf<TTSOverrideMatch>()
            val template = if (replacement.isRegex) replacement.text else Matcher.quoteReplacement(replacement.text)
            var previousEnd = 0
            while (matcher.find()) {
                count++
                val start = matcher.start()
                val end = matcher.end()
                val original = sources.subList(start, end).filter { it.end > it.start }
                val range = if (original.isEmpty()) TTSOverrideMatch(0, 0) else {
                    TTSOverrideMatch(original.minOf { it.start }, original.maxOf { it.end })
                }
                if (range.end > range.start) matched += range
                val previousLength = result.length
                matcher.appendReplacement(result, template)
                nextSources.addAll(sources.subList(previousEnd, start))
                // Newly inserted text inherits the source match; zero-width insertions have no source characters.
                repeat(result.length - previousLength - (start - previousEnd)) { nextSources += range }
                previousEnd = end
            }
            matcher.appendTail(result)
            nextSources.addAll(sources.subList(previousEnd, sources.size))
            current = result.toString()
            sources = nextSources
        }
        val merged = mutableListOf<TTSOverrideMatch>()
        for (range in matched.sortedBy { it.start }) {
            val previous = merged.lastOrNull()
            if (previous != null && range.start <= previous.end) {
                merged[merged.lastIndex] = previous.copy(end = maxOf(previous.end, range.end))
            } else merged += range
        }
        return TTSOverridePreview(current, merged, count)
    }

    /** Null means the rule removed all speech; never wait for an empty TTS utterance. */
    fun prepare(line: TTSHelper.TTSLine): TTSHelper.TTSLine? {
        val speech = replace(line.speakOutMsg)
        return when {
            speech.isBlank() -> null
            speech == line.speakOutMsg -> line
            else -> line.copy(speakOutMsg = speech)
        }
    }
}
