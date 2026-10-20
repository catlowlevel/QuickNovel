package com.lagradost.quicknovel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class QuickStreamResumeTest {
    @get:Rule val temp = TemporaryFolder()

    private val meta = QuickStreamMetaData("A. Writer", "The Glass Harbor", "Test Source")
    private val source = "https://example.com/novel/glass-harbor"

    private fun snapshot(count: Int = 2) =
        QuickStreamData(
            meta,
            "https://example.com/cover.jpg",
            (1..count).map { ChapterData("Chapter $it", "https://example.com/chapter/$it") }.toMutableList(),
            source,
        )

    @Test
    fun savedChapterListsSurviveNewCacheInstancesAndPreserveRawMetadata() {
        val directory = temp.newFolder()
        val saved = snapshot().also { it.data[0] = it.data[0].copy(name = "The Sailor&#39;s Compass") }
        FileQuickStreamCache(directory).save(saved)
        assertEquals(saved, FileQuickStreamCache(directory).load(meta, source))
        assertEquals("The Sailor&#39;s Compass", FileQuickStreamCache(directory).load(meta, source)!!.data[0].name)
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.single().name.matches(Regex("[a-f0-9]{64}\\.json")))
    }

    @Test
    fun olderTemporaryMetadataIsMigratedAndStillWorksAfterTheTemporaryFileIsGone() {
        val directory = temp.newFolder()
        val legacy = temp.newFile()
        val json = DataStore.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(snapshot())
        json.remove("sourceUrl")
        legacy.writeText(json.toString())
        val migrated = FileQuickStreamCache(directory).load(meta, source, legacy)
        assertEquals(snapshot(), migrated)
        assertTrue(legacy.delete())
        assertEquals(snapshot(), FileQuickStreamCache(directory).load(meta, source))
    }

    @Test
    fun missingTruncatedOrEmptyMetadataIsACacheMiss() {
        val directory = temp.newFolder()
        val cache = FileQuickStreamCache(directory)
        assertNull(cache.load(meta, source))
        cache.save(snapshot())
        val file = directory.listFiles()!!.single()
        for (invalid in
            listOf(
                "",
                "{",
                "{}",
                DataStore.mapper.writeValueAsString(snapshot(0)),
                DataStore.mapper.writeValueAsString(
                    snapshot().copy(data = mutableListOf(ChapterData("Missing link", "")))
                ),
            )) {
            file.writeText(invalid)
            assertNull(cache.load(meta, source))
        }
    }

    @Test
    fun anotherSourceOrACopiedBookSnapshotCannotBeReused() {
        val directory = temp.newFolder()
        val cache = FileQuickStreamCache(directory)
        cache.save(snapshot())
        assertNull(cache.load(meta, "$source-another-book"))
        assertNull(cache.load(meta.copy(apiName = "Other Source"), source))
        val file = directory.listFiles()!!.single()
        file.writeText(DataStore.mapper.writeValueAsString(snapshot().copy(meta = meta.copy(author = "Someone Else"))))
        assertNull(cache.load(meta, source))
    }

    @Test
    fun invalidWritesDoNotEraseTheLastUsableSnapshot() {
        val directory = temp.newFolder()
        val cache = FileQuickStreamCache(directory)
        cache.save(snapshot())
        for (invalid in
            listOf(
                snapshot(0),
                snapshot().copy(sourceUrl = null),
                snapshot().copy(data = mutableListOf(ChapterData("Missing link", " "))),
            )) {
            try {
                cache.save(invalid)
                fail("Invalid snapshot was saved")
            } catch (_: IllegalArgumentException) {}
            assertEquals(snapshot(), cache.load(meta, source))
        }
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun migrationStorageFailureDoesNotPreventReadingValidLegacyMetadata() {
        val legacy =
            temp.newFile().apply { writeText(DataStore.mapper.writeValueAsString(snapshot().copy(sourceUrl = null))) }
        val blockedDirectory = temp.newFile()
        assertEquals(snapshot(), FileQuickStreamCache(blockedDirectory).load(meta, source, legacy))
        try {
            FileQuickStreamCache(blockedDirectory).save(snapshot())
            fail("Storage failure should be reported")
        } catch (_: IOException) {}
    }

    @Test
    fun replacingSnapshotFilesNeverExposesPartialJsonToOtherReaders() = runBlocking {
        val directory = temp.newFolder()
        FileQuickStreamCache(directory).save(snapshot())
        val writer =
            async(kotlinx.coroutines.Dispatchers.IO) {
                repeat(20) { FileQuickStreamCache(directory).save(snapshot(if (it % 2 == 0) 2 else 3)) }
            }
        val reader =
            async(kotlinx.coroutines.Dispatchers.IO) {
                repeat(40) {
                    val loaded = FileQuickStreamCache(directory).load(meta, source)
                    assertNotNull(loaded)
                    assertTrue(loaded!!.data.size in 2..3)
                }
            }
        writer.await()
        reader.await()
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun historyOpensSavedMetadataWithoutCallingOrWaitingForTheNetwork() = runBlocking {
        val directory = temp.newFolder()
        FileQuickStreamCache(directory).save(snapshot())
        val opened = CompletableDeferred<QuickStreamData>()
        resumeHistoryStream(
            loadSaved = { FileQuickStreamCache(directory).load(meta, source) },
            openSaved = {
                opened.complete(it)
                true
            },
            openOnline = { fail("Cached resume must not wait for a provider request") },
        )
        assertEquals(snapshot(), opened.await())
    }

    @Test
    fun missingMetadataUsesOnlineFallbackAndWaitsForItToFinish() = runBlocking {
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchFinished = CompletableDeferred<Unit>()
        val resume = async {
            resumeHistoryStream(
                loadSaved = { null },
                openSaved = {
                    fail("There is no saved list to open")
                    false
                },
                openOnline = {
                    fetchStarted.complete(Unit)
                    fetchFinished.await()
                },
            )
        }
        fetchStarted.await()
        assertFalse(resume.isCompleted)
        fetchFinished.complete(Unit)
        resume.await()
    }

    @Test
    fun failedLocalOpenFallsBackOnceAndKeepsTheOnlineErrorVisibleToTheCaller() = runBlocking {
        val events = mutableListOf<String>()
        try {
            resumeHistoryStream(
                loadSaved = { snapshot() },
                openSaved = {
                    events += "local"
                    false
                },
                openOnline = {
                    events += "online"
                    throw IOException("Unavailable")
                },
            )
            fail("Online failure should reach the caller")
        } catch (_: IOException) {}
        assertEquals(listOf("local", "online"), events)
    }

    @Test
    fun aSuspendedLocalOpenDoesNotStartAnUnnecessaryFallbackRequest() = runBlocking {
        val finishOpen = CompletableDeferred<Boolean>()
        val resume = async {
            resumeHistoryStream({ snapshot() }, { finishOpen.await() }, { fail("Local open is still in progress") })
        }
        yield()
        assertFalse(resume.isCompleted)
        finishOpen.complete(true)
        resume.await()
    }

    @Test
    fun refreshedNewChaptersAppendWithoutChangingExistingChapterIndicesOrObjects() {
        val saved = snapshot()
        val fresh = snapshot(4).also { it.data[0] = it.data[0].copy(name = "Updated remote heading") }
        val merged = appendRefreshedChapters(saved, fresh)!!
        assertEquals(4, merged.data.size)
        assertEquals(2, saved.data.size)
        saved.data.indices.forEach { assertSame(saved.data[it], merged.data[it]) }
        assertEquals(fresh.data.drop(2), merged.data.drop(2))
        assertEquals(saved.meta, merged.meta)
        assertEquals(saved.sourceUrl, merged.sourceUrl)
    }

    @Test
    fun shorterOrUnchangedRefreshCannotTruncateTheSavedList() {
        val saved = snapshot(3)
        assertNull(appendRefreshedChapters(saved, snapshot(1)))
        assertNull(appendRefreshedChapters(saved, snapshot(3)))
        assertEquals(3, saved.data.size)
    }

    @Test
    fun insertedReorderedOrReplacedChaptersCannotRetargetExistingCachedBodies() {
        val saved = snapshot()
        val reordered = snapshot(3).also { java.util.Collections.swap(it.data, 0, 1) }
        val inserted = snapshot(3).also { it.data.add(0, ChapterData("Prologue", "/prologue")) }
        val replaced = snapshot(3).also { it.data[1] = it.data[1].copy(url = "/replacement") }
        for (fresh in listOf(reordered, inserted, replaced)) assertNull(appendRefreshedChapters(saved, fresh))
        assertEquals(snapshot(), saved)
    }

    @Test
    fun anotherBookProviderOrInvalidExtensionCannotModifyTheCurrentReader() {
        val saved = snapshot()
        val fresh = snapshot(3)
        for (other in
            listOf(
                fresh.copy(sourceUrl = "$source-other"),
                fresh.copy(meta = meta.copy(apiName = "Other Source")),
                fresh.copy(meta = meta.copy(name = "Different Book")),
                fresh.copy(meta = meta.copy(author = "Different Author")),
                fresh.copy(data = (fresh.data + ChapterData("Invalid", "")).toMutableList()),
            )) {
            assertNull(appendRefreshedChapters(saved, other))
        }
    }
}
