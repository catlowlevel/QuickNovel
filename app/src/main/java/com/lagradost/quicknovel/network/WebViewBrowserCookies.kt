package com.lagradost.quicknovel.network

import android.webkit.CookieManager
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object WebViewBrowserCookies : BrowserCookieStore {
    private const val USER_AGENTS = "browser_session_user_agent"

    override fun cookies(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    override fun saveCookie(url: String, cookie: String) {
        runCatching { CookieManager.getInstance().setCookie(url, cookie) }
    }

    override fun userAgent(url: String): String? = url.toHttpUrlOrNull()?.host?.let { host ->
        getKey<String>(USER_AGENTS, host)
    }

    fun recordUserAgent(url: String, agent: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        if (getKey<String>(USER_AGENTS, host) != agent) setKey(USER_AGENTS, host, agent)
        WebViewResolver.webViewUserAgent = agent
    }
}
