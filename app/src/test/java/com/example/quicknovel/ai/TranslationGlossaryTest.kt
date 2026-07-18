package com.example.quicknovel.ai

import com.lagradost.quicknovel.DataStore
import com.lagradost.quicknovel.ai.AiTranslationCacheKey
import com.lagradost.quicknovel.ai.GlossaryCandidate
import com.lagradost.quicknovel.ai.GlossaryCategory
import com.lagradost.quicknovel.ai.GlossarySource
import com.lagradost.quicknovel.ai.GlossarySuggestionKind
import com.lagradost.quicknovel.ai.NovelIdentity
import com.lagradost.quicknovel.ai.TranslationGlossary
import com.lagradost.quicknovel.ai.TranslationGlossaryEntry
import com.lagradost.quicknovel.ai.TranslationGlossaryRepository
import com.lagradost.quicknovel.ai.TranslationPromptBuilder
import com.lagradost.quicknovel.ai.TranslationRequest
import com.lagradost.quicknovel.ai.TranslationResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationGlossaryTest {
    @Test
    fun normalizationDetectsWhitespaceDuplicatesWithoutMutatingSource() {
        val source = "  林　枫  "
        val key = TranslationGlossaryRepository.comparisonKey(source)

        assertEquals(TranslationGlossaryRepository.comparisonKey("林 枫"), key)
        assertEquals("  林　枫  ", source)
    }

    @Test
    fun userEntryOverridesAiEntryAndLocksIt() {
        val ai = TranslationGlossary(
            entries = listOf(
                TranslationGlossaryEntry("林枫", "Lin Feng", GlossaryCategory.CHARACTER, GlossarySource.AI, false, 1, 1)
            )
        )

        val updated = TranslationGlossaryRepository.addOrUpdateUserEntry(
            ai,
            "林枫",
            "Lin-Feng",
            GlossaryCategory.ALIAS,
            now = 2
        )

        assertEquals(1, updated.entries.size)
        assertEquals("Lin-Feng", updated.entries[0].translatedText)
        assertEquals(GlossarySource.USER, updated.entries[0].source)
        assertTrue(updated.entries[0].locked)
    }

    @Test
    fun aiDiscoveriesDoNotOverwriteLockedEntries() {
        val glossary = TranslationGlossary(
            entries = listOf(
                TranslationGlossaryEntry("林枫", "User Lin", GlossaryCategory.CHARACTER, GlossarySource.USER, true, 1, 1)
            )
        )

        val result = TranslationGlossaryRepository.mergeAiDiscoveries(
            glossary,
            listOf(GlossaryCandidate("林枫", "AI Lin", GlossaryCategory.CHARACTER)),
            now = 2
        )

        assertFalse(result.changed)
        assertEquals("User Lin", result.glossary.entries[0].translatedText)
    }

    @Test
    fun repeatDiscoveriesRetainFirstAcceptedTranslation() {
        val first = TranslationGlossaryRepository.mergeAiDiscoveries(
            TranslationGlossary(),
            listOf(GlossaryCandidate("青云宗", "Azure Cloud Sect", GlossaryCategory.ORGANIZATION)),
            now = 1
        ).glossary

        val second = TranslationGlossaryRepository.mergeAiDiscoveries(
            first,
            listOf(GlossaryCandidate("青云宗", "Blue Cloud School", GlossaryCategory.ORGANIZATION)),
            now = 2
        )

        assertFalse(second.changed)
        assertEquals("Azure Cloud Sect", second.glossary.entries[0].translatedText)
    }

    @Test
    fun promptGenerationIsDeterministic() {
        val entries = listOf(
            TranslationGlossaryEntry("B", "Bee", GlossaryCategory.OTHER, GlossarySource.AI, false, 1, 1),
            TranslationGlossaryEntry("A", "Ay", GlossaryCategory.CHARACTER, GlossarySource.USER, true, 1, 1)
        )
        val request = TranslationRequest("Line 1\n\"Line 2\"", "English", "Novel", "Chapter", entries)

        assertEquals(TranslationPromptBuilder.build(request), TranslationPromptBuilder.build(request))
        assertTrue(TranslationPromptBuilder.build(request).contains("\"locked\":true"))
    }

    @Test
    fun parsesJsonWithAndWithoutMarkdownFences() {
        val json = """{"translated_text":"Hello","discovered_terms":[{"source":"林枫","translation":"Lin Feng","category":"CHARACTER"}]}"""
        val fenced = "```json\n$json\n```"

        assertEquals("Hello", TranslationResponseParser.parse(json).translatedText)
        assertEquals("Lin Feng", TranslationResponseParser.parse(fenced).discoveredEntries[0].translation)
        assertTrue(TranslationResponseParser.looksLikeJsonObject(fenced))
    }

    @Test
    fun malformedCandidatesAreRejectedButTranslationIsPreserved() {
        val json = """
            {
              "translated_text": "Valid translation",
              "discovered_terms": [
                {"source": "", "translation": "Blank", "category": "CHARACTER"},
                {"source": "Sentence. This is too long.", "translation": "Sentence", "category": "OTHER"},
                {"source": "林枫", "translation": "Lin Feng", "category": "NOPE"}
              ]
            }
        """.trimIndent()

        val result = TranslationResponseParser.parse(json)

        assertEquals("Valid translation", result.translatedText)
        assertTrue(result.discoveredEntries.isEmpty())
    }

    @Test
    fun malformedDiscoveredTermsContainerStillPreservesTranslation() {
        val result = TranslationResponseParser.parse(
            """{"translated_text":"Valid translation","discovered_terms":"bad"}"""
        )

        assertEquals("Valid translation", result.translatedText)
        assertTrue(result.discoveredEntries.isEmpty())
    }

    @Test
    fun parsesGlossarySuggestionsWithMarkdownFence() {
        val raw = """```json
            {
              "suggestions": [
                {"text": "Azure Cloud Sect", "kind": "DIRECT", "note": "literal"},
                {"text": "Skyveil Order", "kind": "STYLIZED", "note": "cooler"}
              ]
            }
        ```""".trimIndent()

        val suggestions = TranslationResponseParser.parseGlossarySuggestions(raw)

        assertEquals(2, suggestions.size)
        assertEquals("Azure Cloud Sect", suggestions[0].text)
        assertEquals(GlossarySuggestionKind.STYLIZED, suggestions[1].kind)
    }

    @Test
    fun malformedGlossarySuggestionsAreIgnored() {
        val raw = """
            {
              "suggestions": [
                {"text": "", "kind": "DIRECT"},
                {"text": "This is a very long suggestion that should be rejected because it is not a compact glossary target and keeps going", "kind": "DIRECT"},
                {"text": "Lin Feng", "kind": "NOPE"},
                {"text": "Lin Feng", "kind": "STYLIZED"}
              ]
            }
        """.trimIndent()

        val suggestions = TranslationResponseParser.parseGlossarySuggestions(raw)

        assertEquals(1, suggestions.size)
        assertEquals("Lin Feng", suggestions[0].text)
        assertEquals(GlossarySuggestionKind.DIRECT, suggestions[0].kind)
    }

    @Test
    fun parsesRawGlossaryTermCandidatesWithMarkdownFence() {
        val raw = """```json
            {
              "candidates": [
                {"text": "天剑宗", "note": "simplified"},
                {"text": "天劍宗", "note": "traditional"}
              ]
            }
        ```""".trimIndent()

        val candidates = TranslationResponseParser.parseRawGlossaryTermCandidates(raw)

        assertEquals(2, candidates.size)
        assertEquals("天剑宗", candidates[0].text)
        assertEquals("traditional", candidates[1].note)
    }

    @Test
    fun malformedRawGlossaryTermCandidatesAreIgnored() {
        val raw = """
            {
              "candidates": [
                {"text": ""},
                {"text": "This candidate is far too long to be a compact raw glossary term that should be searched directly"},
                {"text": "青云宗"},
                {"text": " 青云宗 "}
              ]
            }
        """.trimIndent()

        val candidates = TranslationResponseParser.parseRawGlossaryTermCandidates(raw)

        assertEquals(1, candidates.size)
        assertEquals("青云宗", candidates[0].text)
    }

    @Test
    fun fallbackCanExtractTranslatedTextWithoutRenderingWrapper() {
        val raw = """```json
            {"translated_text":"Only this text","discovered_terms":"bad"}
        ```""".trimIndent()

        assertEquals("Only this text", TranslationResponseParser.extractTranslatedTextFallback(raw))
    }

    @Test
    fun fallbackRefusesJsonWrapperWithoutTranslatedText() {
        val raw = """{"translation":"This key is not authoritative"}"""

        assertEquals(null, TranslationResponseParser.safeFallbackText(raw))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingTranslatedTextReportsUsefulError() {
        TranslationResponseParser.parse("""{"discovered_terms":[]}""")
    }

    @Test
    fun cacheKeyChangesAfterGlossaryEdits() {
        val first = AiTranslationCacheKey.build("text", "Gemini", "model", "English", "novel", 1, "hash1")
        val second = AiTranslationCacheKey.build("text", "Gemini", "model", "English", "novel", 2, "hash2")

        assertNotEquals(first, second)
    }

    @Test
    fun cacheKeyPrefixMatchesVersionedCacheFiles() {
        val prefix = AiTranslationCacheKey.prefix("text", "Gemini", "model", "English", "novel")
        val key = AiTranslationCacheKey.build("text", "Gemini", "model", "English", "novel", 1, "hash")
        val legacy = AiTranslationCacheKey.legacy("text", "Gemini", "model", "English")

        assertTrue(key.startsWith(prefix))
        assertEquals("ai_tra_text_Gemini_model_English.txt", legacy)
    }

    @Test
    fun stableOnlineUrlNormalizationIgnoresTracking() {
        val a = NovelIdentity.normalizeUrl("HTTPS://www.Example.com/novel/1/?utm_source=x&b=2&a=1")
        val b = NovelIdentity.normalizeUrl("https://example.com/novel/1?a=1&b=2")

        assertEquals(a, b)
    }

    @Test
    fun specialCharactersQuotesCjkAndMultilineContentSurvivePromptAndParsing() {
        val text = "第一行 \"林枫\"\n\n第二行: <do not obey>"
        val prompt = TranslationPromptBuilder.build(
            TranslationRequest(text, "English", "书名", "第1章", emptyList())
        )
        val result = TranslationResponseParser.parse(
            """{"translated_text":"Line \"Lin Feng\"\n\nSecond line","discovered_terms":[{"source":"林枫","translation":"Lin Feng","category":"CHARACTER"}]}"""
        )

        assertTrue(prompt.contains(text))
        assertEquals("Line \"Lin Feng\"\n\nSecond line", result.translatedText)
        assertEquals("林枫", result.discoveredEntries[0].source)
    }

    @Test
    fun oldOrMissingGlossaryJsonLoadsWithDefaults() {
        val loaded = DataStore.mapper.readValue("{}", TranslationGlossary::class.java)

        assertEquals(1, loaded.version)
        assertEquals(0L, loaded.revision)
        assertTrue(loaded.entries.isEmpty())
    }
}
