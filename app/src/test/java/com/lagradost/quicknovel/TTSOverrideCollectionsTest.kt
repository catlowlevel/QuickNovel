package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSOverrideCollectionsTest {
    @Test
    fun duplicateWarningsExcludeSelfButIncludeDisabledRulesInEitherScope() {
        val rule = TTSWordOverride(find = "Li", replacement = "Lee")
        val duplicate = rule.copy(id = "duplicate", find = "li", enabled = false)
        val base = TTSOverrideCollections(local = listOf(rule))
        assertTrue(base.duplicateScopes(rule, TTSOverrideScope.Local).isEmpty())
        assertEquals(listOf(TTSOverrideScope.Local), base.duplicateScopes(rule, null))
        val both = base.copy(local = listOf(rule, duplicate), global = listOf(duplicate))
        assertEquals(TTSOverrideScope.entries, both.duplicateScopes(rule, TTSOverrideScope.Local))
        assertTrue(both.duplicateScopes(rule.copy(caseSensitive = true), TTSOverrideScope.Local).isEmpty())
        assertTrue(both.duplicateScopes(rule.copy(isRegex = true), TTSOverrideScope.Local).isEmpty())
    }

    @Test
    fun editingAndMovingARulePreservesDestinationDuplicatesAndAllEditedOptions() {
        val original = TTSWordOverride(find = "Li", replacement = "Lee")
        val sibling = TTSWordOverride(find = "Wu", replacement = "Woo")
        val existing = original.copy(id = "existing", replacement = "other")
        val saved = TTSOverrideCollections(local = listOf(original, sibling), global = listOf(existing))
        val draft = original.copy(replacement = "\$1", find = "(Li)", isRegex = true, caseSensitive = true, enabled = false)
        val moved = saved.saveRule(draft, TTSOverrideScope.Local, TTSOverrideScope.Global)
        assertEquals(listOf(sibling), moved.local)
        assertEquals(listOf(existing, draft), moved.global)
        assertEquals(listOf(original, sibling), saved.local)
        assertEquals(listOf(existing), saved.global)
    }

    @Test
    fun editorMovePreviewRemovesTheOldScopeVersionWithoutChangingSavedRules() {
        val global = TTSWordOverride(find = "Li", replacement = "global")
        val local = global.copy(id = "local", find = "X", replacement = "local")
        val saved = TTSOverrideCollections(local = listOf(local), global = listOf(global))
        val preview = saved.saveRule(global.copy(find = "Wu", replacement = "draft"), TTSOverrideScope.Global, TTSOverrideScope.Local)
        assertTrue(preview.global.isEmpty())
        assertEquals("Li draft", TTSOverrideEngine(preview.effectiveRules()).preview("Li Wu").text)
        assertEquals("global Wu", TTSOverrideEngine(saved.effectiveRules()).replace("Li Wu"))
        assertEquals(listOf(global), saved.global)
        assertEquals(listOf(local), saved.local)
    }

    @Test
    fun editorMoveDoesNotOverwriteAnExistingDestinationIdentity() {
        val source = TTSWordOverride(id = "shared", find = "Li", replacement = "Lee")
        val destination = source.copy(find = "Wu", replacement = "Woo")
        val moved = TTSOverrideCollections(local = listOf(destination), global = listOf(source))
            .saveRule(source.copy(replacement = "edited"), TTSOverrideScope.Global, TTSOverrideScope.Local)
        assertTrue(moved.global.isEmpty())
        assertEquals(destination, moved.local.first())
        assertNotEquals(destination.id, moved.local.last().id)
        assertEquals("edited", moved.local.last().replacement)
    }

    @Test
    fun editorSameScopeSavePreservesOrderAndNewRuleUsesTheSelectedScope() {
        val rule = TTSWordOverride(find = "Li", replacement = "Lee")
        val next = TTSWordOverride(find = "Wu", replacement = "Woo")
        val saved = TTSOverrideCollections(local = listOf(rule, next))
        val edited = rule.copy(replacement = "new")
        assertEquals(listOf(edited, next), saved.saveRule(edited, TTSOverrideScope.Local, TTSOverrideScope.Local).local)
        val added = saved.saveRule(next, null, TTSOverrideScope.Global)
        assertEquals(saved.local, added.local)
        assertEquals(listOf(next), added.global)
    }

    @Test
    fun enabledLocalRulesReplaceMatchingGlobalsIncludingLiteralCaseVariants() {
        val global = TTSWordOverride(find = "li", replacement = "global")
        val local = TTSWordOverride(find = "Li", replacement = "local")
        val collections = TTSOverrideCollections(local = listOf(local), global = listOf(global))
        assertTrue(collections.overridesGlobal(global))
        assertEquals(listOf(local), collections.effectiveRules())
        assertEquals("local", TTSOverrideEngine(collections.effectiveRules()).replace("Li"))
    }

    @Test
    fun disabledOrInvalidLocalsFallBackToTheValidGlobalRule() {
        val global = TTSWordOverride(find = "(Li)", replacement = "Lee", isRegex = true)
        val base = TTSOverrideCollections(global = listOf(global))
        for (local in listOf(
            global.copy(id = "local-disabled", replacement = "local", enabled = false),
            global.copy(id = "local-invalid", replacement = "\$2"),
        )) {
            val collections = base.copy(local = listOf(local))
            assertFalse(collections.overridesGlobal(global))
            assertEquals("Lee", TTSOverrideEngine(collections.effectiveRules()).replace("Li"))
        }
    }

    @Test
    fun localThenGlobalOrderIsStableAndDifferentMatchingOptionsRemainSeparate() {
        val first = TTSWordOverride(find = "A", replacement = "B")
        val second = TTSWordOverride(find = "B", replacement = "C")
        val duplicate = TTSWordOverride(find = "A", replacement = "wrong")
        val strict = TTSWordOverride(find = "A", replacement = "strict", caseSensitive = true)
        val regex = TTSWordOverride(find = "A", replacement = "regex", isRegex = true)
        val last = TTSWordOverride(find = "C", replacement = "D")
        val collections = TTSOverrideCollections(local = listOf(first, second), global = listOf(duplicate, strict, regex, last))
        assertEquals(listOf(first, second, strict, regex, last), collections.effectiveRules())
        assertEquals("D", TTSOverrideEngine(collections.effectiveRules()).replace("A"))
    }

    @Test
    fun copyingAGlobalRuleCreatesAnIndependentLocalRuleWithAllOptionsPreserved() {
        val global = TTSWordOverride(find = "(Li)", replacement = "\$1 Lee", isRegex = true, caseSensitive = true)
        val original = TTSOverrideCollections(global = listOf(global))
        val copied = original.transfer(global.id, TTSOverrideScope.Global, copy = true)
        val local = copied.local.single()
        assertEquals(listOf(global), copied.global)
        assertNotEquals(global.id, local.id)
        assertEquals(global.copy(id = local.id), local)
        val edited = copied.withRules(TTSOverrideScope.Local, copied.local.withRule(local.copy(replacement = "local")))
        assertEquals("local", TTSOverrideEngine(edited.effectiveRules()).replace("Li"))
        assertEquals(global, edited.global.single())
        assertTrue(original.local.isEmpty())
    }

    @Test
    fun movingLocalToGlobalPreservesExistingDestinationRulesAndRemovesOnlyTheSource() {
        val rule = TTSWordOverride(find = "Li", replacement = "Lee", enabled = false)
        val existing = rule.copy(id = "existing", replacement = "other")
        val original = TTSOverrideCollections(local = listOf(rule), global = listOf(existing))
        val moved = original.transfer(rule.id, TTSOverrideScope.Local, copy = false)
        assertTrue(moved.local.isEmpty())
        assertEquals(listOf(existing, rule), moved.global)
        assertEquals(listOf(rule), original.local)
    }

    @Test
    fun movingGlobalToLocalDoesNotOverwriteACollidingDestinationIdentity() {
        val global = TTSWordOverride(id = "shared", find = "Li", replacement = "Lee")
        val local = global.copy(find = "Wu", replacement = "Woo")
        val moved = TTSOverrideCollections(local = listOf(local), global = listOf(global))
            .transfer(global.id, TTSOverrideScope.Global, copy = false)
        assertTrue(moved.global.isEmpty())
        assertEquals(local, moved.local.first())
        assertNotEquals(local.id, moved.local.last().id)
        assertEquals(global.copy(id = moved.local.last().id), moved.local.last())
    }

    @Test
    fun localStorageIsIsolatedPerBookAndExistingGlobalStorageStaysCompatible() {
        val global = TTSWordOverride(find = "Li", replacement = "global")
        val local = global.copy(id = "local", replacement = "local")
        val a = ttsPlaybackBookKey("quick", "Provider A", "Author", "Same Title")
        val b = ttsPlaybackBookKey("quick", "Provider B", "Author", "Same Title")
        val values = mapOf(
            EPUB_TTS_WORD_OVERRIDES to DataStore.mapper.writeValueAsString(TTSWordOverrides(listOf(global))),
            ttsLocalOverrideKey(a) to DataStore.mapper.writeValueAsString(TTSWordOverrides(listOf(local))),
        )
        fun load(bookId: String): TTSOverrideCollections {
            fun rules(key: String) = values[key]?.let {
                DataStore.mapper.readValue(it, TTSWordOverrides::class.java).rules
            }.orEmpty()
            return TTSOverrideCollections(local = rules(ttsLocalOverrideKey(bookId)), global = rules(EPUB_TTS_WORD_OVERRIDES))
        }
        assertNotEquals(ttsLocalOverrideKey(a), ttsLocalOverrideKey(b))
        assertEquals(ttsLocalOverrideKey(a), ttsLocalOverrideKey(ttsPlaybackBookKey("quick", "Provider A", "Author", "Same Title")))
        assertEquals("local", TTSOverrideEngine(load(a).effectiveRules()).replace("Li"))
        assertEquals("global", TTSOverrideEngine(load(b).effectiveRules()).replace("Li"))
    }

    @Test
    fun unsavedLocalPreviewUsesTheSamePrecedenceAsSpeechAndReaderHighlights() {
        val global = TTSWordOverride(find = "Li", replacement = "global")
        val saved = TTSOverrideCollections(global = listOf(global))
        val draft = global.copy(id = "local-draft", replacement = "local")
        val combined = saved.withRules(TTSOverrideScope.Local, listOf(draft)).effectiveRules()
        val engine = TTSOverrideEngine(combined)
        assertEquals("local", engine.preview("Li").text)
        assertEquals("local", engine.prepare(TTSHelper.TTSLine("Li", 100, 102, 4))!!.speakOutMsg)
        val highlights = TTSReaderOverrideHighlights()
        highlights.updateRules(combined)
        assertEquals(listOf(TTSOverrideMatch(0, 2)), highlights.forParagraph(listOf(TTSHelper.TTSLine("Li", 100, 102, 4)), 4, 100, 102))
        assertTrue(saved.local.isEmpty())
        assertEquals("global", TTSOverrideEngine(saved.effectiveRules()).replace("Li"))
    }
}
