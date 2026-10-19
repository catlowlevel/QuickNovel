package com.lagradost.quicknovel.ai

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Cached results deliberately exclude API credentials, endpoint paths and the original chapter. */
data class AiCachedSummary(
    val sourceKey: String,
    val language: String,
    val provider: AiProvider,
    val profileName: String,
    val model: String,
    val output: AiSummaryOutput,
    val createdAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
)

interface AiSummaryCache {
    suspend fun load(chapter: AiChapter, language: String): AiCachedSummary?
    suspend fun save(result: AiCachedSummary)
}

class FileAiSummaryCache(private val directory: File) : AiSummaryCache {
    private val mutex = Mutex()

    override suspend fun load(chapter: AiChapter, language: String): AiCachedSummary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = chapter.resultKey(language)
            val file = File(directory, "$key.json")
            if (!file.isFile || file.length() > MAX_BYTES) return@withLock null
            try {
                val bytes = file.inputStream().use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        if (buffer.size() + count > MAX_BYTES) return@withLock null
                        buffer.write(block, 0, count)
                    }
                    buffer.toByteArray()
                }
                DataStore.mapper.readValue<AiCachedSummary>(bytes).takeIf {
                    it.version == 1 && it.sourceKey == key && it.language == language &&
                        it.output.text.isNotBlank() && it.model.isNotBlank()
                }
            } catch (_: IOException) { null }
        }
    }

    override suspend fun save(result: AiCachedSummary): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(result.version == 1 && result.sourceKey.matches(Regex("[a-f0-9]{64}")))
            require(result.output.text.isNotBlank() && result.model.isNotBlank())
            val bytes = DataStore.mapper.writeValueAsBytes(result)
            if (bytes.size > MAX_BYTES) throw IOException("Summary exceeds the cache size limit")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create summary storage")
            val target = File(directory, "${result.sourceKey}.json")
            val temp = File(directory, ".${result.sourceKey}-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
                coroutineContext.ensureActive()
                // Same-directory rename is atomic on Android; keep the existing result on any failure.
                if (!temp.renameTo(target)) throw IOException("Could not replace saved summary")
            } finally { temp.delete() }
        }
    }

    private companion object { const val MAX_BYTES = 2_000_000 }
}
