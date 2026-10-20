package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileAiSummaryCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val chapter =
        AiChapter("book-a", "chapter-4", "Novel", "Chapter 4", "Private original chapter body, not cache metadata.")

    private fun record(source: AiChapter = chapter, language: String = "en") =
        AiCachedSummary(
            source.resultKey(language),
            language,
            AiProvider.GEMINI,
            "Personal",
            "chosen-model",
            AiSummaryOutput("Saved recap. 你好。", truncated = true),
            createdAt = 12345,
        )

    @Test
    fun newCacheInstancesReadSavedOutputAndMetadataWithoutStoringCredentialsOrOriginalText() = runBlocking {
        val directory = temp.newFolder()
        FileAiSummaryCache(directory).save(record())
        assertEquals(record(), FileAiSummaryCache(directory).load(chapter, "en"))
        val json = directory.listFiles()!!.single().readText()
        assertFalse(json.contains(chapter.text))
        assertFalse(json.contains("apiKey"))
        assertFalse(json.contains("baseUrl"))
        assertTrue(directory.listFiles()!!.single().name.matches(Regex("[a-f0-9]{64}\\.json")))
    }

    @Test
    fun bookChapterSourceAndLanguageChangesCannotReuseAnotherResult() = runBlocking {
        val cache = FileAiSummaryCache(temp.newFolder())
        cache.save(record())
        for ((source, language) in
            listOf(
                chapter.copy(bookId = "book-b") to "en",
                chapter.copy(chapterId = "chapter-5") to "en",
                chapter.copy(text = "Updated original") to "en",
                chapter to "id",
            )) {
            assertNull(cache.load(source, language))
        }
        assertEquals(record(), cache.load(chapter, "en"))
    }

    @Test
    fun copiedCorruptUnsupportedOrEmptyCacheEntriesAreTreatedAsMisses() = runBlocking {
        val directory = temp.newFolder()
        val cache = FileAiSummaryCache(directory)
        val file = File(directory, "${chapter.resultKey("en")}.json")
        val invalid =
            listOf(
                "not json",
                "{}",
                DataStore.mapper.writeValueAsString(record().copy(version = 2)),
                DataStore.mapper.writeValueAsString(record().copy(sourceKey = "0".repeat(64))),
                DataStore.mapper.writeValueAsString(record().copy(language = "id")),
                DataStore.mapper.writeValueAsString(record().copy(output = AiSummaryOutput(" "))),
                DataStore.mapper.writeValueAsString(record().copy(model = "")),
            )
        for (body in invalid) {
            file.writeText(body)
            assertNull(cache.load(chapter, "en"))
        }
        cache.save(record())
        assertEquals(record(), cache.load(chapter, "en"))
    }

    @Test
    fun truncatedOrOversizeFilesDoNotBecomeValidSavedSummaries() = runBlocking {
        val directory = temp.newFolder()
        val cache = FileAiSummaryCache(directory)
        val file = File(directory, "${chapter.resultKey("en")}.json")
        file.writeText(DataStore.mapper.writeValueAsString(record()).take(50))
        assertNull(cache.load(chapter, "en"))
        file.writeBytes(ByteArray(2_000_001))
        assertNull(cache.load(chapter, "en"))
    }

    @Test
    fun successfulRegenerationReplacesOneFileAndInvalidWritesPreserveTheOldPaidResult() = runBlocking {
        val directory = temp.newFolder()
        val cache = FileAiSummaryCache(directory)
        cache.save(record())
        try {
            cache.save(record().copy(output = AiSummaryOutput("")))
            fail("Empty output must not erase a saved result")
        } catch (_: IllegalArgumentException) {}
        assertEquals(record(), cache.load(chapter, "en"))
        val replacement = record().copy(output = AiSummaryOutput("New recap"), model = "new-model")
        cache.save(replacement)
        assertEquals(replacement, FileAiSummaryCache(directory).load(chapter, "en"))
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun writeFailuresAreReportedAndTemporaryFilesAreRemoved() = runBlocking {
        val blocked = FileAiSummaryCache(temp.newFile())
        try {
            blocked.save(record())
            fail("Storage path is a file")
        } catch (_: IOException) {}
        val directory = temp.newFolder()
        val target = File(directory, "${chapter.resultKey("en")}.json").apply { mkdir() }
        File(target, "keep").writeText("Prevent replacement with a file")
        try {
            FileAiSummaryCache(directory).save(record())
            fail("Target is a nonempty directory")
        } catch (_: IOException) {}
        assertTrue(File(target, "keep").isFile)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun concurrentCacheInstancesNeverExposeHalfWrittenJson() = runBlocking {
        val directory = temp.newFolder()
        FileAiSummaryCache(directory).save(record())
        val reader = FileAiSummaryCache(directory)
        val valid = (0..12).map { if (it == 0) record().output.text else "Complete recap $it" }.toSet()
        val writers =
            (1..12).map { index ->
                async(Dispatchers.IO) {
                    FileAiSummaryCache(directory).save(record().copy(output = AiSummaryOutput("Complete recap $index")))
                }
            }
        repeat(24) { assertTrue(reader.load(chapter, "en")!!.output.text in valid) }
        writers.awaitAll()
        assertTrue(reader.load(chapter, "en")!!.output.text in valid)
        assertEquals(1, directory.listFiles()!!.size)
    }
}
