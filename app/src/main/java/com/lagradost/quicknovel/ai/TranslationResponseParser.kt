package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.DataStore

object TranslationResponseParser {
    fun parse(raw: String): TranslationResult {
        val node = readJsonObject(raw)
        val translated = node.get("translated_text")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("AI response did not include translated_text")

        val discovered = node.get("discovered_terms")
        val candidates = if (discovered?.isArray == true) {
            discovered.mapNotNull { candidate ->
                if (!candidate.isObject) return@mapNotNull null
                val source = candidate.get("source")?.asText()?.trim().orEmpty()
                val translation = candidate.get("translation")?.asText()?.trim().orEmpty()
                val category = candidate.get("category")?.asText()?.trim()?.uppercase()?.let {
                    runCatching { GlossaryCategory.valueOf(it) }.getOrNull()
                } ?: return@mapNotNull null
                if (!TranslationGlossaryRepository.isValidTermPair(source, translation)) return@mapNotNull null
                GlossaryCandidate(source, translation, category)
            }
        } else {
            emptyList()
        }

        return TranslationResult(translated, candidates)
    }

    fun parseGlossarySuggestions(raw: String): List<GlossarySuggestion> {
        val node = readJsonObject(raw)
        val suggestions = node.get("suggestions")
        if (suggestions?.isArray != true) {
            throw IllegalArgumentException("AI response did not include suggestions")
        }

        return suggestions.mapNotNull { suggestion ->
            if (!suggestion.isObject) return@mapNotNull null
            val text = suggestion.get("text")?.asText()?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            if (text.length > 80 || text.lines().size > 2) return@mapNotNull null
            val kind = suggestion.get("kind")?.asText()?.trim()?.uppercase()?.let {
                runCatching { GlossarySuggestionKind.valueOf(it) }.getOrNull()
            } ?: GlossarySuggestionKind.DIRECT
            val note = suggestion.get("note")?.asText()?.trim().orEmpty().take(120)
            GlossarySuggestion(text, kind, note)
        }.distinctBy {
            TranslationGlossaryRepository.comparisonKey(it.text)
        }
    }

    fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        extractJsonFence(trimmed)?.let { return it }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end <= start) throw IllegalArgumentException("AI response was not a JSON object")
        return trimmed.substring(start, end + 1)
    }

    fun extractTranslatedTextFallback(raw: String): String? {
        return runCatching {
            val node = readJsonObject(raw)
            node.get("translated_text")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun extractPartialTranslatedText(raw: String): String? {
        val marker = "\"translated_text\""
        val markerIndex = raw.indexOf(marker)
        if (markerIndex == -1) return null
        val colonIndex = raw.indexOf(':', markerIndex + marker.length)
        if (colonIndex == -1) return null
        val quoteIndex = raw.indexOf('"', colonIndex + 1)
        if (quoteIndex == -1) return null
        val builder = StringBuilder()
        var escaped = false
        for (i in quoteIndex + 1 until raw.length) {
            val c = raw[i]
            if (escaped) {
                when (c) {
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    '"', '\\', '/' -> builder.append(c)
                    else -> builder.append(c)
                }
                escaped = false
            } else {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> return builder.toString()
                    else -> builder.append(c)
                }
            }
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    fun safeFallbackText(raw: String): String? {
        extractTranslatedTextFallback(raw)?.let { return it }
        return if (looksLikeJsonObject(raw)) null else raw
    }

    fun looksLikeJsonObject(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return true
        return extractJsonFence(trimmed) != null
    }

    private fun readJsonObject(raw: String): JsonNode {
        val node = DataStore.mapper.readTree(extractJson(raw))
        if (!node.isObject) throw IllegalArgumentException("AI response was not a JSON object")
        return node
    }

    private fun extractJsonFence(trimmed: String): String? {
        if (!trimmed.startsWith("```")) return null
        val firstLineEnd = trimmed.indexOf('\n').takeIf { it != -1 } ?: return null
        val fenceHeader = trimmed.substring(3, firstLineEnd).trim()
        if (fenceHeader.isNotBlank() && !fenceHeader.equals("json", ignoreCase = true)) return null
        val closingFenceStart = trimmed.lastIndexOf("```")
        if (closingFenceStart <= firstLineEnd) return null
        val inner = trimmed.substring(firstLineEnd + 1, closingFenceStart).trim()
        return inner.takeIf { it.startsWith("{") && it.endsWith("}") }
    }
}
