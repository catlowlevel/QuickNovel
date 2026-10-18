package com.lagradost.quicknovel.network.utils

import android.webkit.CookieManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object CookiesUtils {
    /** Parses a raw cookie string into a key-value Map */
    fun parseCookieMap(cookie: String?): Map<String, String> {
        if (cookie.isNullOrBlank()) return emptyMap()
        return cookie.split(";").mapNotNull { pair ->
            val split = pair.split("=", limit = 2)
            val key = split.getOrNull(0)?.trim() ?: ""
            val value = split.getOrNull(1)?.trim() ?: ""
            if (key.isNotBlank() && value.isNotBlank()) {
                key to value
            } else {
                null
            }
        }.toMap()
    }

    /** Converts a cookie Map back into a single string for HTTP headers */
    fun Map<String, String>.toCookieString(): String {
        return this.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** CookieManager includes applicable domain cookies and filters by path and secure scheme. */
    fun getAllCookiesForUrl(url: String): Map<String, String> {
        val manager = CookieManager.getInstance()
        if (url.toHttpUrlOrNull() == null) return emptyMap()
        return parseCookieMap(manager.getCookie(url))
    }


    /** Removes Cloudflare clearance cookies for a host to force a clean bypass. */
    fun clearCookiesForHost(url: HttpUrl) {
        val manager = CookieManager.getInstance()
        val uri = url.toString()
        val host = url.host
        val rootDomain = url.topPrivateDomain() ?: host

        // Expired clearance must not erase unrelated login or provider preference cookies.
        val deletion = "cf_clearance=; Max-Age=0; Path=/"
        manager.setCookie(uri, deletion)
        manager.setCookie(uri, "$deletion; Domain=$host")
        manager.setCookie(uri, "$deletion; Domain=$rootDomain")
        manager.flush()
    }
}
