package com.lagradost.quicknovel.ai

import com.lagradost.quicknovel.AbstractBook
import com.lagradost.quicknovel.QuickBook
import com.lagradost.quicknovel.RegularBook
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object NovelIdentity {
    fun stableId(book: AbstractBook): String {
        return when (book) {
            is QuickBook -> {
                val firstChapterUrl = book.data.data.firstOrNull()?.url
                val identityUrl = firstChapterUrl?.let { book.resolveUrl(it) } ?: book.data.poster
                val normalized = identityUrl?.let { normalizeUrl(it) }
                val raw = listOf("online", book.data.meta.apiName, normalized ?: book.data.meta.name)
                    .joinToString("|")
                "online_${md5(raw)}"
            }
            is RegularBook -> {
                val identifiers = book.data.metadata.identifiers
                    ?.mapNotNull { id -> id.value?.takeIf { it.isNotBlank() } }
                    ?.sorted()
                    .orEmpty()
                val raw = if (identifiers.isNotEmpty()) {
                    listOf("epub-id").plus(identifiers).joinToString("|")
                } else {
                    val authors = book.data.metadata.authors?.joinToString("|") { author ->
                        listOfNotNull(author.firstname, author.lastname).joinToString(" ")
                    }.orEmpty()
                    val resources = book.data.resources.resourceMap.keys.sorted().take(50).joinToString("|")
                    listOf("epub-fallback", book.title(), authors, resources, book.size().toString()).joinToString("|")
                }
                "local_${md5(raw)}"
            }
            else -> "book_${md5(listOf(book::class.java.name, book.title(), book.size().toString()).joinToString("|"))}"
        }
    }

    fun normalizeUrl(url: String): String {
        return try {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: ""
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.split("&")
                ?.filter { it.isNotBlank() }
                ?.filterNot {
                    val key = it.substringBefore("=").lowercase(Locale.ROOT)
                    key.startsWith("utm_") || key == "ref" || key == "fbclid" || key == "gclid"
                }
                ?.sorted()
                ?.joinToString("&")
                .orEmpty()
            "$scheme://$host$port$path${if (query.isNotBlank()) "?$query" else ""}"
        } catch (_: Throwable) {
            url.trim()
        }
    }

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
