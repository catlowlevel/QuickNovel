package com.lagradost.quicknovel

import java.util.UUID

internal enum class TTSOverrideScope {
    Local,
    Global,
}

internal fun TTSWordOverride.hasSameMatch(other: TTSWordOverride): Boolean =
    isRegex == other.isRegex &&
        caseSensitive == other.caseSensitive &&
        find.equals(other.find, ignoreCase = !isRegex && !caseSensitive)

internal data class TTSOverrideCollections(
    val local: List<TTSWordOverride> = emptyList(),
    val global: List<TTSWordOverride> = emptyList(),
) {
    fun rules(scope: TTSOverrideScope): List<TTSWordOverride> =
        when (scope) {
            TTSOverrideScope.Local -> local
            TTSOverrideScope.Global -> global
        }

    fun withRules(scope: TTSOverrideScope, rules: List<TTSWordOverride>): TTSOverrideCollections =
        when (scope) {
            TTSOverrideScope.Local -> copy(local = rules.toList())
            TTSOverrideScope.Global -> copy(global = rules.toList())
        }

    fun overridesGlobal(rule: TTSWordOverride): Boolean =
        local.any { it.enabled && it.validationError() == null && it.hasSameMatch(rule) }

    /** Matching options define duplicates; disabled rules still deserve a warning. */
    fun duplicateScopes(rule: TTSWordOverride, source: TTSOverrideScope?): List<TTSOverrideScope> =
        TTSOverrideScope.entries.filter { scope ->
            rules(scope).any { (scope != source || it.id != rule.id) && it.hasSameMatch(rule) }
        }

    /** Also used for unsaved previews, so a scope change removes the old rule there too. */
    fun saveRule(rule: TTSWordOverride, from: TTSOverrideScope?, to: TTSOverrideScope): TTSOverrideCollections {
        if (from == to) return withRules(to, rules(to).withRule(rule))
        val inserted = if (rules(to).any { it.id == rule.id }) rule.copy(id = UUID.randomUUID().toString()) else rule
        val updated = withRules(to, rules(to) + inserted)
        return if (from == null) updated else updated.withRules(from, rules(from).filterNot { it.id == rule.id })
    }

    /** Local rules run first; disabled/invalid locals allow the matching global rule to apply. */
    fun effectiveRules(): List<TTSWordOverride> =
        local.filter { it.enabled && it.validationError() == null } + global.filterNot(::overridesGlobal)

    fun transfer(id: String, from: TTSOverrideScope, copy: Boolean): TTSOverrideCollections {
        val rule = rules(from).firstOrNull { it.id == id } ?: return this
        val to = if (from == TTSOverrideScope.Local) TTSOverrideScope.Global else TTSOverrideScope.Local
        val destination = rules(to)
        val inserted =
            if (copy || destination.any { it.id == id }) rule.copy(id = UUID.randomUUID().toString()) else rule
        val updated = withRules(to, destination + inserted)
        return if (copy) updated else updated.withRules(from, rules(from).filterNot { it.id == id })
    }
}

internal fun ttsLocalOverrideKey(bookId: String): String = "$EPUB_TTS_LOCAL_WORD_OVERRIDES/$bookId"
