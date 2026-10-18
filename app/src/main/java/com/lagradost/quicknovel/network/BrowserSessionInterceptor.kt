package com.lagradost.quicknovel.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

internal interface BrowserCookieStore {
    fun cookies(url: String): String?
    fun saveCookie(url: String, cookie: String)
    fun userAgent(url: String): String?
}

private data class BrowserRequestOrigin(val url: HttpUrl, val cookies: String?, val userAgent: String?)

/** Application interceptors run before redirects; network-only request tags are discarded by OkHttp. */
internal class BrowserRequestOriginInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = request.tag(BrowserRequestOrigin::class.java)
            ?: BrowserRequestOrigin(request.url, request.header("Cookie"), request.header("User-Agent"))
        return chain.proceed(request.newBuilder().tag(BrowserRequestOrigin::class.java, origin).build())
    }
}

/** Runs on every network hop, using CookieManager's URL scoping instead of a host-only cache. */
internal class BrowserSessionInterceptor(private val store: BrowserCookieStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.tag(BrowserRequestOrigin::class.java)
            ?: BrowserRequestOrigin(request.url, request.header("Cookie"), request.header("User-Agent"))
        val url = request.url.toString()
        val shared = cookiePairs(store.cookies(url))
        val sameOrigin = request.url.scheme == original.url.scheme && request.url.host == original.url.host &&
            request.url.port == original.url.port
        val cookies = (if (sameOrigin) cookiePairs(original.cookies) else emptyMap()) + shared
        val builder = request.newBuilder()
            .removeHeader("Cookie").removeHeader("User-Agent")
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val providerAgent = if (sameOrigin) request.header("User-Agent") ?: original.userAgent
            else original.userAgent ?: request.header("User-Agent")
        val agent = if (shared.containsKey("cf_clearance")) store.userAgent(url) ?: providerAgent else providerAgent
        if (agent != null) builder.header("User-Agent", agent)
        val response = chain.proceed(builder.build())
        response.headers.values("Set-Cookie").forEach { store.saveCookie(url, it) }
        return response
    }

    private fun cookiePairs(value: String?): Map<String, String> = buildMap {
        value.orEmpty().split(';').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator > 0) {
                val name = pair.substring(0, separator).trim()
                if (name.isNotEmpty()) putIfAbsent(name, pair.substring(separator + 1).trim())
            }
        }
    }
}
