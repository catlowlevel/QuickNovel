package com.lagradost.quicknovel

import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal const val EXTRA_REFRESH_CHAPTER_LIST = "refresh_saved_chapter_list"

/** Chapter metadata survives process/cache cleanup; chapter bodies keep their existing storage. */
internal class FileQuickStreamCache(private val directory: File) {
    private fun file(meta: QuickStreamMetaData): File {
        val identity = DataStore.mapper.writeValueAsBytes(meta)
        val key = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    fun load(meta: QuickStreamMetaData, sourceUrl: String, legacyFile: File? = null): QuickStreamData? {
        if (sourceUrl.isBlank()) return null
        for (candidate in listOfNotNull(file(meta), legacyFile)) {
            val saved =
                try {
                    DataStore.mapper.readValue<QuickStreamData>(candidate)
                } catch (_: IOException) {
                    continue
                }
            if (
                saved.meta != meta ||
                    saved.data.isEmpty() ||
                    saved.data.any { it.url.isBlank() } ||
                    (saved.sourceUrl != null && saved.sourceUrl != sourceUrl)
            )
                continue
            val snapshot = saved.copy(sourceUrl = sourceUrl)
            if (candidate == legacyFile) {
                // A failed migration must not prevent reading a valid existing snapshot.
                try {
                    save(snapshot)
                } catch (_: IOException) {}
            }
            return snapshot
        }
        return null
    }

    fun save(snapshot: QuickStreamData) {
        require(!snapshot.sourceUrl.isNullOrBlank() && snapshot.data.isNotEmpty())
        require(snapshot.data.none { it.url.isBlank() })
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create reader metadata storage")
        val target = file(snapshot.meta)
        val temp = File.createTempFile(".reader-", ".tmp", directory)
        try {
            temp.writeBytes(DataStore.mapper.writeValueAsBytes(snapshot))
            if (!temp.renameTo(target)) throw IOException("Could not replace saved reader metadata")
        } finally {
            temp.delete()
        }
    }
}

/** A failed local open can still fall back to the normal online entry point. */
internal suspend fun resumeHistoryStream(
    loadSaved: () -> QuickStreamData?,
    openSaved: suspend (QuickStreamData) -> Boolean,
    openOnline: suspend () -> Unit,
) {
    val saved = loadSaved()
    if (saved != null && openSaved(saved)) return
    openOnline()
}

/** Existing indices address on-disk chapter bodies, reading progress and narration bookmarks. */
internal fun appendRefreshedChapters(saved: QuickStreamData, fresh: QuickStreamData): QuickStreamData? {
    if (
        saved.meta != fresh.meta ||
            saved.sourceUrl.isNullOrBlank() ||
            saved.sourceUrl != fresh.sourceUrl ||
            fresh.data.size <= saved.data.size ||
            fresh.data.any { it.url.isBlank() } ||
            saved.data.indices.any { saved.data[it].url != fresh.data[it].url }
    )
        return null
    return saved.copy(
        poster = fresh.poster ?: saved.poster,
        data = (saved.data + fresh.data.drop(saved.data.size)).toMutableList(),
    )
}

internal fun StreamResponse.quickStreamData(apiName: String): QuickStreamData =
    QuickStreamData(QuickStreamMetaData(author, name, apiName), posterUrl, data.toMutableList(), sourceUrl = url)
