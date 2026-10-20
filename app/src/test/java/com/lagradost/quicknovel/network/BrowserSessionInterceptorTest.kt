package com.lagradost.quicknovel.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class BrowserSessionInterceptorTest {
    private class Store : BrowserCookieStore {
        val cookies = mutableListOf<Cookie>()
        val agents = mutableMapOf<String, String>()
        val saved = mutableListOf<Pair<String, String>>()

        override fun cookies(url: String): String? {
            val target = url.toHttpUrl()
            return cookies
                .filter { it.expiresAt > System.currentTimeMillis() && it.matches(target) }
                .sortedByDescending { it.path.length }
                .joinToString("; ") { "${it.name}=${it.value}" }
                .takeIf { it.isNotEmpty() }
        }

        override fun saveCookie(url: String, cookie: String) {
            saved += url to cookie
            val parsed = Cookie.parse(url.toHttpUrl(), cookie) ?: return
            cookies.removeAll { it.name == parsed.name && it.domain == parsed.domain && it.path == parsed.path }
            cookies += parsed
        }

        override fun userAgent(url: String) = agents[url.toHttpUrl().host]
    }

    private data class Seen(val path: String, val host: String, val cookies: String?, val agent: String?)

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var store: Store
    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var respond: (RecordedRequest) -> MockResponse = { MockResponse().setBody("ok") }

    private fun url(path: String = "/", host: String = "127.0.0.1") =
        server.url(path).newBuilder().host(host).build().toString()

    private fun get(path: String = "/", headers: Map<String, String> = emptyMap()) {
        val request = Request.Builder().url(url(path)).header("User-Agent", "provider-agent")
        headers.forEach { (name, value) -> request.header(name, value) }
        client.newCall(request.build()).execute().use { response ->
            assertEquals(200, response.code)
            response.body?.string()
        }
    }

    @Before
    fun setUp() {
        store = Store()
        client =
            OkHttpClient.Builder()
                .addInterceptor(BrowserRequestOriginInterceptor())
                .addNetworkInterceptor(BrowserSessionInterceptor(store))
                .build()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen +=
                        Seen(
                            request.path.orEmpty(),
                            request.getHeader("Host").orEmpty(),
                            request.getHeader("Cookie"),
                            request.getHeader("User-Agent"),
                        )
                    return respond(request)
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun manualClearanceAndProviderCookieParametersAreBothSentWithTheBrowserIdentity() {
        store.saveCookie(url(), "cf_clearance=manual; Path=/")
        store.agents["127.0.0.1"] = "browser-agent"
        get(headers = mapOf("Cookie" to "toc_show=10000"))
        assertEquals("toc_show=10000; cf_clearance=manual", seen.single().cookies)
        assertEquals("browser-agent", seen.single().agent)
    }

    @Test
    fun laterManualVerificationReplacesStaleCookiesWithoutAnAppRestart() {
        store.saveCookie(url(), "cf_clearance=first; Path=/")
        get(headers = mapOf("Cookie" to "cf_clearance=stale"))
        store.saveCookie(url(), "cf_clearance=second; Path=/")
        get(headers = mapOf("Cookie" to "cf_clearance=stale"))
        assertEquals(listOf("cf_clearance=first", "cf_clearance=second"), seen.map { it.cookies })
    }

    @Test
    fun responseCookiesAreAvailableToTheNextRequestWithoutLosingEqualsSigns() {
        respond = { MockResponse().addHeader("Set-Cookie", "session=a=b=c; Path=/; HttpOnly") }
        get()
        get()
        assertNull(seen.first().cookies)
        assertEquals("session=a=b=c", seen.last().cookies)
        assertTrue(store.saved.any { it.second.contains("HttpOnly") })
    }

    @Test
    fun crossHostRedirectCannotForwardCookiesOrClearanceIdentityFromTheFirstSite() {
        store.saveCookie(url(), "cf_clearance=private; Path=/")
        store.saveCookie(url(host = "localhost"), "other_session=allowed; Path=/")
        store.agents["127.0.0.1"] = "private-browser-agent"
        respond = { request ->
            if (request.path == "/redirect") {
                MockResponse().setResponseCode(302).addHeader("Location", url("/target", "localhost"))
            } else MockResponse()
        }
        get("/redirect", mapOf("Cookie" to "provider_secret=private"))
        val target = seen.last()
        assertTrue(target.host.startsWith("localhost:"))
        assertEquals("other_session=allowed", target.cookies)
        assertEquals("provider-agent", target.agent)
    }

    @Test
    fun sameOriginRedirectUsesNewResponseCookiesAndPreservesProviderParameters() {
        store.saveCookie(url(), "cf_clearance=old; Path=/")
        respond = { request ->
            if (request.path == "/redirect") {
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/target")
                    .addHeader("Set-Cookie", "cf_clearance=new; Path=/")
            } else MockResponse()
        }
        get("/redirect", mapOf("Cookie" to "toc_show=10000"))
        assertEquals("toc_show=10000; cf_clearance=new", seen.last().cookies)
    }

    @Test
    fun pathScopedCookiesAreNotAddedToUnrelatedPagesOnTheSameHost() {
        store.saveCookie(url(), "private=token; Path=/restricted")
        get("/public")
        get("/restricted/chapter")
        assertNull(seen.first().cookies)
        assertEquals("private=token", seen.last().cookies)
    }

    @Test
    fun expiredClearanceStopsApplyingAndTheProviderUserAgentIsRestored() {
        store.agents["127.0.0.1"] = "browser-agent"
        store.saveCookie(url(), "cf_clearance=old; Path=/")
        get()
        store.saveCookie(url(), "cf_clearance=; Max-Age=0; Path=/")
        get()
        assertNull(seen.last().cookies)
        assertEquals("provider-agent", seen.last().agent)
    }

    @Test
    fun theMostSpecificPathCookieWinsWhenTheBrowserReturnsDuplicateNames() {
        store.saveCookie(url(), "session=general; Path=/")
        store.saveCookie(url(), "session=specific; Path=/novel")
        get("/novel/chapter")
        assertEquals("session=specific", seen.single().cookies)
    }

    @Test
    fun ordinaryBrowserCookiesDoNotOverrideProviderUserAgentsAndEmptyCookieValuesSurvive() {
        store.saveCookie(url(), "session=normal; Path=/")
        store.agents["127.0.0.1"] = "browser-agent"
        get(headers = mapOf("Cookie" to "flag=; token=a=b"))
        assertEquals("flag=; token=a=b; session=normal", seen.single().cookies)
        assertEquals("provider-agent", seen.single().agent)
    }

    @Test
    fun requestsWithoutBrowserCookiesKeepExistingProviderHeaders() {
        get(headers = mapOf("Cookie" to "provider=original"))
        assertEquals("provider=original", seen.single().cookies)
        assertEquals("provider-agent", seen.single().agent)
        get()
        assertFalse(seen.last().cookies?.isNotEmpty() == true)
    }

    @Test
    fun intentionalSameOriginUserAgentChangesFromOtherInterceptorsArePreserved() {
        val adjusted =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "intentional-agent").build())
                }
                .build()
        adjusted.newCall(Request.Builder().url(url()).header("User-Agent", "provider-agent").build()).execute().use {
            it.body?.string()
        }
        assertEquals("intentional-agent", seen.single().agent)
    }
}
