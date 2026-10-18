package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TTSWordOverridesTest {
    @Test
    fun literalRulesEscapeRegexCharactersAndReplaceEveryOccurrence() {
        val engine = TTSOverrideEngine(listOf(TTSWordOverride(find = "C++", replacement = "see plus plus")))
        assertEquals("see plus plus and see plus plus; CCC", engine.replace("C++ and c++; CCC"))
    }

    @Test
    fun caseSensitivityIsOptionalAndIgnoringCaseHandlesNonAsciiText() {
        val rule = TTSWordOverride(find = "Élan", replacement = "elan")
        assertEquals("elan elan elan", TTSOverrideEngine(listOf(rule)).replace("Élan élan ÉLAN"))
        assertEquals("elan élan ÉLAN", TTSOverrideEngine(listOf(rule.copy(caseSensitive = true))).replace("Élan élan ÉLAN"))
    }

    @Test
    fun caseSensitivityAlsoControlsRegexMatchingWithoutChangingCapturedLetterCase() {
        val rule = TTSWordOverride(find = "\\b(Li)\\b", replacement = "[\$1]", isRegex = true)
        val input = "li Li LI Lily"
        assertEquals("[li] [Li] [LI] Lily", TTSOverrideEngine(listOf(rule)).replace(input))
        assertEquals("li [Li] LI Lily", TTSOverrideEngine(listOf(rule.copy(caseSensitive = true))).replace(input))
        // Explicit flags inside a regex can override the checkbox's default.
        val forceIgnoreCase = rule.copy(find = "(?i)\\b(Li)\\b", caseSensitive = true)
        assertEquals("[li] [Li] [LI] Lily", TTSOverrideEngine(listOf(forceIgnoreCase)).replace(input))
        val forceSensitive = rule.copy(find = "(?-i)\\b(Li)\\b")
        assertEquals("li [Li] LI Lily", TTSOverrideEngine(listOf(forceSensitive)).replace(input))
    }

    @Test
    fun regexCanMatchWholeWordsWithoutChangingPartsOfOtherWords() {
        val rule = TTSWordOverride(find = "\\bLi\\b", replacement = "Lee", isRegex = true)
        assertEquals("Lee met Lily and Lee.", TTSOverrideEngine(listOf(rule)).replace("Li met Lily and li."))
        // Literal mode must never interpret the same text as a word-boundary pattern.
        assertEquals("Li", TTSOverrideEngine(listOf(rule.copy(isRegex = false))).replace("Li"))
    }

    @Test
    fun replacementDollarSignsAndBackslashesStayLiteralOutsideRegexMode() {
        val replacement = "\$1\\pronunciation"
        val rule = TTSWordOverride(find = "Li", replacement = replacement)
        assertEquals(replacement, TTSOverrideEngine(listOf(rule)).replace("Li"))
    }

    @Test
    fun regexReplacementCanReorderNumberedGroupsForEveryMatch() {
        val rule = TTSWordOverride(find = "(\\w+)\\s+(\\w+)", replacement = "\$2 \$1", isRegex = true)
        assertNull(rule.validationError())
        assertEquals("Wei Li, Yu Han.", TTSOverrideEngine(listOf(rule)).replace("Li Wei, Han Yu."))
    }

    @Test
    fun namedAndOptionalGroupsExpandWithUnmatchedGroupsAsEmptyText() {
        val rule = TTSWordOverride(
            find = "(?<given>Li)(?:\\s+(?<family>Wei))?",
            replacement = "\${family}/\${given}", isRegex = true,
        )
        assertNull(rule.validationError())
        assertEquals("Wei/Li and /Li", TTSOverrideEngine(listOf(rule)).replace("Li Wei and Li"))
    }

    @Test
    fun nestedCapturesCountButNonCapturingGroupsDoNot() {
        val rule = TTSWordOverride(find = "(?:Mr )?((Li) Wei)", replacement = "\$2: \$1", isRegex = true)
        assertEquals("Li: Li Wei", TTSOverrideEngine(listOf(rule)).replace("Mr Li Wei"))
    }

    @Test
    fun regexLiteralEscapesAndCapturedSpecialCharactersAreNotReinterpreted() {
        val rule = TTSWordOverride(find = "(Li)", replacement = "\\\$1\\\\path", isRegex = true)
        assertNull(rule.validationError())
        assertEquals("\$1\\path", TTSOverrideEngine(listOf(rule)).replace("Li"))
        val capture = rule.copy(find = "^(.+)$", replacement = "\$1")
        assertEquals("\$2\\path", TTSOverrideEngine(listOf(capture)).replace("\$2\\path"))
    }

    @Test
    fun invalidReferencesAreRejectedEvenWhenThePatternCannotMatchThePreview() {
        val rule = TTSWordOverride(find = "(?!)((Li))", isRegex = true)
        for (replacement in listOf("\$3", "\${missing}", "\$", "\\", "\${")) {
            assertEquals(replacement, TTSWordOverride.Error.InvalidReplacement, rule.copy(replacement = replacement).validationError())
        }
        // These characters still need no escaping in ordinary text rules.
        assertNull(rule.copy(find = "Li", replacement = "\$3\\", isRegex = false).validationError())
    }

    @Test
    fun wholeMatchAndMultiDigitReferencesFollowRegexReplacementNumbering() {
        val rule = TTSWordOverride(find = "(Li)", replacement = "\$10:\$0", isRegex = true)
        assertEquals("Li0:Li", TTSOverrideEngine(listOf(rule)).replace("Li"))
        val ten = rule.copy(find = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", replacement = "\$10:\$1")
        assertEquals("j:a", TTSOverrideEngine(listOf(ten)).replace("abcdefghij"))
    }

    @Test
    fun invalidSavedGroupReferencesAreSkippedWithoutBreakingOtherRules() {
        val invalid = TTSWordOverride(find = "(Li)", replacement = "\$2", isRegex = true)
        val valid = TTSWordOverride(find = "Li", replacement = "Lee")
        assertEquals("Lee", TTSOverrideEngine(listOf(invalid, valid)).replace("Li"))
    }

    @Test
    fun rulesRunInVisibleOrderAndDisabledRulesHaveNoEffect() {
        val first = TTSWordOverride(find = "Li", replacement = "Lee")
        val second = TTSWordOverride(find = "Lee", replacement = "Lay")
        assertEquals("Lay", TTSOverrideEngine(listOf(first, second)).replace("Li"))
        assertEquals("Lee", TTSOverrideEngine(listOf(second, first)).replace("Li"))
        assertEquals("Li", TTSOverrideEngine(listOf(first.copy(enabled = false), second)).replace("Li"))
    }

    @Test
    fun emptyFindAndMalformedRegexAreRejectedButLiteralBracketsAreValid() {
        assertEquals(TTSWordOverride.Error.EmptyFind, TTSWordOverride().validationError())
        assertEquals(TTSWordOverride.Error.InvalidRegex, TTSWordOverride(find = "[", isRegex = true).validationError())
        assertNull(TTSWordOverride(find = "[").validationError())
        // Spaces can intentionally be matched for speech cleanup.
        assertNull(TTSWordOverride(find = " ").validationError())
    }

    @Test
    fun damagedSavedRulesDoNotPreventValidRulesOrNarrationFromWorking() {
        val rules = listOf(
            TTSWordOverride(find = "[", isRegex = true),
            TTSWordOverride(find = "", replacement = "bad"),
            TTSWordOverride(find = "Li", replacement = "Lee"),
        )
        assertEquals("Lee spoke.", TTSOverrideEngine(rules).replace("Li spoke."))
    }

    @Test
    fun zeroWidthRegexCanAddAPrefixWithoutLoopingOrReprocessingItsOwnOutput() {
        val rule = TTSWordOverride(find = "^", replacement = "Narrator: ", isRegex = true)
        assertEquals("Narrator: Hello.", TTSOverrideEngine(listOf(rule)).replace("Hello."))
    }

    @Test
    fun anEntirelyRemovedSentenceIsSkippedInsteadOfQueuedAsEmptySpeech() {
        val line = TTSHelper.TTSLine("Skip me", 10, 17, 2)
        val rule = TTSWordOverride(find = "Skip me", replacement = "")
        assertNull(TTSOverrideEngine(listOf(rule)).prepare(line))
        assertNull(TTSOverrideEngine(listOf(rule.copy(replacement = " \n\t"))).prepare(line))
    }

    @Test
    fun speechReplacementsPreserveOriginalChapterAndCharacterPositions() {
        val original = TTSHelper.TTSLine("Li spoke.", 200, 209, 4)
        val engine = TTSOverrideEngine(listOf(TTSWordOverride(find = "Li", replacement = "a much longer pronunciation")))
        val spoken = engine.prepare(original)!!
        assertEquals("a much longer pronunciation spoke.", spoken.speakOutMsg)
        assertEquals(original.startChar, spoken.startChar)
        assertEquals(original.endChar, spoken.endChar)
        assertEquals(original.index, spoken.index)
        assertEquals("Li spoke.", original.speakOutMsg)
        assertSame(original, TTSOverrideEngine(emptyList()).prepare(original))
    }

    @Test
    fun editingRulesChangesPreparedQueueIdentityWithoutMutatingThePreviousSnapshot() {
        val line = TTSHelper.TTSLine("Li", 20, 22, 1)
        val rule = TTSWordOverride(find = "Li", replacement = "Lee")
        val rules = mutableListOf(rule)
        val oldEngine = TTSOverrideEngine(rules)
        val prefetched = oldEngine.prepare(line)
        rules[0] = rule.copy(replacement = "Lay")
        val updated = TTSOverrideEngine(rules).prepare(line)
        // TTSSession compares the whole prepared line before reusing prefetched speech.
        assertNotEquals(prefetched, updated)
        assertEquals(prefetched, oldEngine.prepare(line))
        assertEquals(line, TTSOverrideEngine(emptyList()).prepare(line))
    }

    @Test
    fun previewOfAnUnsavedEditPreservesRuleOrderAndDoesNotChangeSavedRules() {
        val first = TTSWordOverride(find = "Li", replacement = "Lee")
        val second = TTSWordOverride(find = "Lee", replacement = "Lay")
        val saved = listOf(first, second)
        val draft = first.copy(replacement = "Lyn")
        val preview = saved.withRule(draft)
        assertEquals(listOf(first.id, second.id), preview.map { it.id })
        assertEquals("Lyn", TTSOverrideEngine(preview).replace("Li"))
        assertEquals("Lay", TTSOverrideEngine(saved).replace("Li"))
        assertEquals(first, saved.first())
    }

    @Test
    fun previewOfANewRuleAppendsItAfterSavedRulesWithoutSavingTheDraft() {
        val first = TTSWordOverride(find = "Li", replacement = "Lee")
        val saved = listOf(first)
        val draft = TTSWordOverride(find = "Lee", replacement = "Lay")
        val preview = saved.withRule(draft)
        assertEquals("Lay", TTSOverrideEngine(preview).replace("Li"))
        assertEquals(listOf(first), saved)
        // Repeated preview/save of the same draft replaces it rather than duplicating it.
        assertEquals(preview, preview.withRule(draft))
    }

    @Test
    fun rulesSurviveAppJsonStorageWithTheirOrderIdentityAndOptions() {
        val saved = TTSWordOverrides(listOf(
            TTSWordOverride(find = "\\bLi\\b", replacement = "Lee", isRegex = true, caseSensitive = true),
            TTSWordOverride(find = "\"old\"", replacement = "\$1\\new\n", enabled = false),
        ))
        val json = DataStore.mapper.writeValueAsString(saved)
        val restored = DataStore.mapper.readValue(json, TTSWordOverrides::class.java)
        assertEquals(saved, restored)
        assertEquals("Lee", TTSOverrideEngine(restored.rules).replace("Li"))
    }
}
