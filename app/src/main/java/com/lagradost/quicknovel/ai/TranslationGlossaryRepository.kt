package com.lagradost.quicknovel.ai

import android.content.Context
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.EPUB_TRANSLATION_GLOSSARY
import java.text.Normalizer
import java.util.Locale

class TranslationGlossaryRepository(private val context: Context) {
    fun load(novelId: String): TranslationGlossary {
        val glossary = context.getKey<TranslationGlossary>(EPUB_TRANSLATION_GLOSSARY, novelId)
            ?: TranslationGlossary(revision = 0L)
        return glossary.copy(entries = sortEntries(glossary.entries))
    }

    fun save(novelId: String, glossary: TranslationGlossary) {
        context.setKey(EPUB_TRANSLATION_GLOSSARY, novelId, glossary.copy(entries = sortEntries(glossary.entries)))
    }

    fun mergeAiDiscoveries(
        novelId: String,
        candidates: List<GlossaryCandidate>,
        sourceText: String? = null,
        now: Long = System.currentTimeMillis()
    ): MergeResult {
        val current = load(novelId)
        val merged = mergeAiDiscoveries(current, candidates, sourceText, now)
        if (merged.changed) save(novelId, merged.glossary)
        return merged
    }

    fun addOrUpdateUserEntry(
        novelId: String,
        sourceText: String,
        translatedText: String,
        category: GlossaryCategory,
        now: Long = System.currentTimeMillis()
    ): TranslationGlossary {
        val current = load(novelId)
        val updated = addOrUpdateUserEntry(current, sourceText, translatedText, category, now)
        save(novelId, updated)
        return updated
    }

    fun deleteEntry(novelId: String, sourceText: String): TranslationGlossary {
        val current = load(novelId)
        val key = comparisonKey(sourceText)
        val entries = current.entries.filterNot { comparisonKey(it.sourceText) == key }
        val updated = if (entries.size == current.entries.size) {
            current
        } else {
            current.copy(revision = current.revision + 1, entries = sortEntries(entries))
        }
        if (updated !== current) save(novelId, updated)
        return updated
    }

    companion object {
        data class MergeResult(
            val glossary: TranslationGlossary,
            val addedCount: Int,
            val changed: Boolean
        )

        fun comparisonKey(text: String): String {
            return Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
                .replace(Regex("\\s+"), " ")
        }

        fun sortEntries(entries: List<TranslationGlossaryEntry>): List<TranslationGlossaryEntry> {
            return entries.sortedWith(
                compareBy<TranslationGlossaryEntry> { it.category.name }
                    .thenBy { comparisonKey(it.sourceText) }
                    .thenBy { it.sourceText }
            )
        }

        fun contentHash(entries: List<TranslationGlossaryEntry>): String {
            val stable = sortEntries(entries).joinToString("\n") {
                listOf(it.sourceText, it.translatedText, it.category.name, it.source.name, it.locked.toString())
                    .joinToString("\u001f")
            }
            return NovelIdentity.md5(stable)
        }

        fun mergeAiDiscoveries(
            glossary: TranslationGlossary,
            candidates: List<GlossaryCandidate>,
            sourceText: String? = null,
            now: Long = System.currentTimeMillis()
        ): MergeResult {
            val entries = glossary.entries.toMutableList()
            val seenCandidates = hashSetOf<String>()
            var added = 0

            for (candidate in candidates) {
                val source = candidate.source.trim()
                val translation = candidate.translation.trim()
                if (!isValidTermPair(source, translation)) continue
                if (sourceText != null && !isCandidateFromSourceText(sourceText, source)) continue

                val key = comparisonKey(source)
                if (!seenCandidates.add(key)) continue

                val existingIndex = entries.indexOfFirst { comparisonKey(it.sourceText) == key }
                if (existingIndex != -1) {
                    // Existing entries, especially locked/user entries, remain authoritative.
                    continue
                }

                entries += TranslationGlossaryEntry(
                    sourceText = source,
                    translatedText = translation,
                    category = candidate.category,
                    source = GlossarySource.AI,
                    locked = false,
                    createdAt = now,
                    updatedAt = now
                )
                added++
            }

            if (added == 0) return MergeResult(glossary.copy(entries = sortEntries(glossary.entries)), 0, false)
            return MergeResult(
                glossary.copy(revision = glossary.revision + 1, entries = sortEntries(entries)),
                added,
                true
            )
        }

        fun addOrUpdateUserEntry(
            glossary: TranslationGlossary,
            sourceText: String,
            translatedText: String,
            category: GlossaryCategory,
            now: Long = System.currentTimeMillis()
        ): TranslationGlossary {
            val source = sourceText.trim()
            val translation = translatedText.trim()
            require(source.isNotBlank()) { "Source term is required" }
            require(translation.isNotBlank()) { "Translation is required" }

            val entries = glossary.entries.toMutableList()
            val key = comparisonKey(source)
            val index = entries.indexOfFirst { comparisonKey(it.sourceText) == key }
            if (index == -1) {
                entries += TranslationGlossaryEntry(
                    sourceText = source,
                    translatedText = translation,
                    category = category,
                    source = GlossarySource.USER,
                    locked = true,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                val old = entries[index]
                entries[index] = old.copy(
                    translatedText = translation,
                    category = category,
                    source = GlossarySource.USER,
                    locked = true,
                    updatedAt = now
                )
            }
            return glossary.copy(revision = glossary.revision + 1, entries = sortEntries(entries))
        }

        fun isValidTermPair(source: String, translation: String): Boolean {
            if (source.isBlank() || translation.isBlank()) return false
            if (comparisonKey(source).equals(comparisonKey(translation), ignoreCase = true)) return false
            if (source.length > 80 || translation.length > 120) return false
            if (source.lines().size > 1 || translation.lines().size > 1) return false
            val sourceWords = source.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val translationWords = translation.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (sourceWords.size > 8 || translationWords.size > 10) return false
            val sentenceMarks = ".!?。！？"
            if (source.length > 24 && source.any { it in sentenceMarks }) return false
            if (translation.length > 32 && translation.any { it in sentenceMarks }) return false
            return true
        }

        fun isCandidateFromSourceText(sourceText: String, candidateSource: String): Boolean {
            val normalizedSourceText = Normalizer.normalize(sourceText, Normalizer.Form.NFKC)
            val normalizedCandidate = Normalizer.normalize(candidateSource.trim(), Normalizer.Form.NFKC)
            return normalizedCandidate.isNotBlank() && normalizedSourceText.contains(normalizedCandidate)
        }
    }
}
