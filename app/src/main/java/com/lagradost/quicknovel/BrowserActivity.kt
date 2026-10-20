package com.lagradost.quicknovel

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.lagradost.quicknovel.CommonActivity.updateLocale
import com.lagradost.quicknovel.databinding.ActivityBrowserBinding
import com.lagradost.quicknovel.network.WebViewBrowserCookies
import com.lagradost.quicknovel.network.WebViewResolver
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BrowserActivity : AppCompatActivity() {
    companion object {
        private const val URL = "browser_url"
        private const val HISTORY = "browser_history"

        fun open(context: Context, url: String) {
            val page = browserUrlOrNull(url)
            if (page == null) {
                Toast.makeText(context, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(Intent(context, BrowserActivity::class.java).putExtra(URL, page))
        }
    }

    private lateinit var binding: ActivityBrowserBinding
    private var browser: WebView? = null
    private var lastUrl = ""

    private data class ExternalBrowsers(val candidates: List<ResolveInfo>, val direct: ResolveInfo?)

    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)
        updateLocale()
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.toolbar.setNavigationContentDescription(R.string.close)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.browser_actions)
        binding.toolbar.overflowIcon =
            ContextCompat.getDrawable(this, R.drawable.ic_baseline_more_vert_24)?.apply {
                setTint(colorFromAttribute(R.attr.textColor))
            }
        for (id in listOf(R.id.browser_back, R.id.browser_forward, R.id.browser_reload)) {
            binding.toolbar.menu.findItem(id).icon?.setTint(colorFromAttribute(R.attr.textColor))
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.browser_back,
                R.id.browser_menu_back -> if (browser?.canGoBack() == true) browser?.goBack()
                R.id.browser_forward,
                R.id.browser_menu_forward -> if (browser?.canGoForward() == true) browser?.goForward()
                R.id.browser_reload -> reload()
                R.id.browser_open_external -> openExternalBrowser()
                R.id.browser_copy_link -> copyLink()
                R.id.browser_share_link -> shareLink()
                R.id.browser_clear_cookies -> confirmClearCookies()
            }
            true
        }
        updateNavigation()
        binding.retry.setOnClickListener { reload() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = browser
                    if (view?.canGoBack() == true) view.goBack() else finish()
                }
            },
        )
        lastUrl = browserUrlOrNull(savedInstanceState?.getString(URL) ?: intent.getStringExtra(URL)).orEmpty()
        if (lastUrl.isEmpty()) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (createBrowser()) {
            val history = savedInstanceState?.getBundle(HISTORY)
            if (history == null || browser?.restoreState(history) == null) browser?.loadUrl(lastUrl)
            updateNavigation()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(): Boolean =
        try {
            val view = WebView(this)
            browser = view
            binding.content.addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            WebViewBrowserCookies.recordUserAgent(lastUrl, view.settings.userAgentString)
            binding.toolbar.title = lastUrl.toHttpUrlOrNull()?.host
            binding.toolbar.subtitle = lastUrl.toHttpUrlOrNull()?.host
            view.webChromeClient =
                object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, progress: Int) {
                        binding.progress.progress = progress
                        binding.progress.isVisible = progress < 100 && !binding.errorPanel.isVisible
                        updateNavigation()
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        binding.toolbar.title = title?.takeIf { it.isNotBlank() } ?: lastUrl.toHttpUrlOrNull()?.host
                    }
                }
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val blocked = browserUrlOrNull(request.url.toString()) == null
                        if (blocked && request.isForMainFrame && request.hasGesture()) {
                            Toast.makeText(this@BrowserActivity, R.string.browser_invalid_url, Toast.LENGTH_SHORT)
                                .show()
                        }
                        return blocked
                    }

                    @Deprecated("Used on Android 6")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        browserUrlOrNull(url) == null

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        if (request.isForMainFrame)
                            request.url.host?.let { host ->
                                WebViewResolver.capturedHeaders[host] = request.requestHeaders.toMap()
                            }
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        updatePage(view, url)
                        binding.toolbar.title = lastUrl.toHttpUrlOrNull()?.host
                        binding.content.isVisible = true
                        binding.errorPanel.isVisible = false
                        binding.progress.progress = 0
                        binding.progress.isVisible = true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        updatePage(view, url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        binding.progress.isVisible = false
                        updatePage(view, view.url ?: url)
                        flushCookies()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) showError(R.string.browser_load_failed)
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                        if (browserUrlOrNull(error.url) == lastUrl) showError(R.string.browser_certificate_error)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        binding.content.removeView(view)
                        browser = null
                        view.destroy()
                        showError(R.string.browser_load_failed)
                        return true
                    }
                }
            true
        } catch (_: Exception) {
            destroyBrowser()
            showError(R.string.browser_unavailable)
            false
        }

    private fun updatePage(view: WebView, url: String?) {
        browserUrlOrNull(url)?.let { page ->
            val schemeChanged = page.toHttpUrlOrNull()?.scheme != lastUrl.toHttpUrlOrNull()?.scheme
            lastUrl = page
            binding.toolbar.subtitle = page.toHttpUrlOrNull()?.host
            WebViewBrowserCookies.recordUserAgent(page, view.settings.userAgentString)
            if (schemeChanged) updateExternalBrowserLabel()
        }
        updateNavigation()
    }

    private fun updateNavigation() {
        val menu = binding.toolbar.menu
        for (id in listOf(R.id.browser_back, R.id.browser_menu_back)) {
            menu.findItem(id).apply {
                isEnabled = browser?.canGoBack() == true
                icon?.alpha = if (isEnabled) 255 else 85
            }
        }
        for (id in listOf(R.id.browser_forward, R.id.browser_menu_forward)) {
            menu.findItem(id).apply {
                isEnabled = browser?.canGoForward() == true
                icon?.alpha = if (isEnabled) 255 else 85
            }
        }
    }

    private fun currentUrl(): String = browserUrlOrNull(browser?.url) ?: lastUrl

    @Suppress("DEPRECATION")
    private fun externalBrowsers(): ExternalBrowsers {
        // Resolve a neutral address, then target a browser explicitly. Resolving the novel's
        // address directly can send it straight back to QuickNovel's deep-link activity.
        val scheme = currentUrl().toHttpUrlOrNull()?.scheme ?: return ExternalBrowsers(emptyList(), null)
        val probe =
            Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://browser.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        val candidates =
            packageManager
                .queryIntentActivities(probe, flags)
                .filter { candidate ->
                    candidate.activityInfo.packageName != packageName &&
                        candidate.filter?.let { filter ->
                            (0 until filter.countDataAuthorities()).all { filter.getDataAuthority(it).host == "*" }
                        } == true
                }
                .distinctBy { it.activityInfo.packageName }
        val preferred =
            packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return ExternalBrowsers(
            candidates,
            candidates.firstOrNull { it.activityInfo.packageName == preferred } ?: candidates.singleOrNull(),
        )
    }

    private fun updateExternalBrowserLabel() {
        val name =
            externalBrowsers().direct?.let { candidate ->
                runCatching { candidate.activityInfo.applicationInfo.loadLabel(packageManager).toString().trim() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        binding.toolbar.menu.findItem(R.id.browser_open_external).title =
            if (name == null) {
                getString(R.string.browser_open_system)
            } else getString(R.string.browser_open_named, name)
    }

    private fun openExternalBrowser() {
        val url = currentUrl()
        val browsers = externalBrowsers()
        val targets =
            browsers.candidates.map { candidate ->
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).apply {
                    component = ComponentName(candidate.activityInfo.packageName, candidate.activityInfo.name)
                }
            }
        val target =
            targets.firstOrNull { it.component?.packageName == browsers.direct?.activityInfo?.packageName }
                ?: targets.singleOrNull()
                ?: targets.firstOrNull()?.let { first ->
                    Intent.createChooser(first, getString(R.string.browser_open_system)).apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
                    }
                }
        if (target == null) {
            Toast.makeText(this, R.string.browser_no_external_browser, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(target)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_no_external_browser, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.browser_no_external_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLink() {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.browser_copy_link), currentUrl()))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(this, R.string.browser_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLink() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentUrl())
        try {
            startActivity(Intent.createChooser(share, getString(R.string.result_share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_no_share_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearCookies() {
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_clear_cookies)
            .setMessage(R.string.browser_clear_cookies_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.browser_clear_cookies) { _, _ ->
                browser?.stopLoading()
                CookieManager.getInstance().removeAllCookies {
                    flushCookies()
                    if (!isFinishing && !isDestroyed) reload()
                }
            }
            .show()
    }

    private fun reload() {
        binding.errorPanel.isVisible = false
        binding.content.isVisible = true
        if (browser == null) {
            if (createBrowser()) browser?.loadUrl(lastUrl)
        } else if (browser?.url == null) browser?.loadUrl(lastUrl) else browser?.reload()
    }

    private fun showError(message: Int) {
        binding.progress.isVisible = false
        binding.content.isVisible = false
        binding.errorMessage.setText(message)
        binding.errorPanel.isVisible = true
        updateNavigation()
    }

    private fun flushCookies() {
        Unit.ioSafe { CookieManager.getInstance().flush() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(URL, lastUrl)
        browser?.let { view -> outState.putBundle(HISTORY, Bundle().also { view.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
        updateExternalBrowserLabel()
    }

    override fun onPause() {
        browser?.onPause()
        flushCookies()
        super.onPause()
    }

    private fun destroyBrowser() {
        val view = browser ?: return
        browser = null
        binding.content.removeView(view)
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    override fun onDestroy() {
        destroyBrowser()
        super.onDestroy()
    }
}
