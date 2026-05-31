package com.lagradost.quicknovel

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.quicknovel.databinding.ActivityWebviewBinding
import com.lagradost.quicknovel.network.WebViewResolver

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding
    private var targetUrl: String? = null

    companion object {
        const val EXTRA_URL = "extra_url"

        fun newIntent(context: Context, url: String): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.webviewToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.webviewToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Get target URL
        targetUrl = intent.getStringExtra(EXTRA_URL)
        if (targetUrl.isNullOrEmpty()) {
            finish()
            return
        }

        // Configure WebView settings
        val webSettings = binding.webview.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Synchronize User-Agent with WebViewResolver to avoid mismatch when bypassing Cloudflare
        // Safely read the User-Agent from the existing WebView instance to avoid blocking the main thread
        val defaultUa = WebViewResolver.webViewUserAgent
        if (defaultUa != null) {
            webSettings.userAgentString = defaultUa
        } else {
            val systemUa = webSettings.userAgentString
            webSettings.userAgentString = systemUa
            WebViewResolver.webViewUserAgent = systemUa
        }

        // Accept cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webview, true)

        // Setup WebViewClient
        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.webviewProgress.visibility = View.VISIBLE
                binding.webviewProgress.progress = 0
                url?.let {
                    binding.webviewToolbar.subtitle = Uri.parse(it).host
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.webviewProgress.visibility = View.GONE
                url?.let {
                    binding.webviewToolbar.subtitle = Uri.parse(it).host
                }
                // Update menu items
                invalidateOptionsMenu()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }
        }

        // Setup WebChromeClient
        binding.webview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.webviewProgress.progress = newProgress
                if (newProgress == 100) {
                    binding.webviewProgress.visibility = View.GONE
                } else {
                    binding.webviewProgress.visibility = View.VISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrEmpty()) {
                    binding.webviewToolbar.title = title
                }
            }
        }

        // Load target URL
        binding.webview.loadUrl(targetUrl!!)

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_webview, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_go_back)?.isEnabled = binding.webview.canGoBack()
        menu?.findItem(R.id.action_go_forward)?.isEnabled = binding.webview.canGoForward()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                binding.webview.reload()
                true
            }
            R.id.action_go_back -> {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                }
                true
            }
            R.id.action_go_forward -> {
                if (binding.webview.canGoForward()) {
                    binding.webview.goForward()
                }
                true
            }
            R.id.action_clear_cookies -> {
                CookieManager.getInstance().removeAllCookies(null)
                CommonActivity.showToast("Cookies cleared")
                binding.webview.reload()
                true
            }
            R.id.action_copy_link -> {
                val currentUrl = binding.webview.url ?: targetUrl
                currentUrl?.let {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("URL", it)
                    clipboard.setPrimaryClip(clip)
                    CommonActivity.showToast("Link copied to clipboard")
                }
                true
            }
            R.id.action_share -> {
                val currentUrl = binding.webview.url ?: targetUrl
                currentUrl?.let {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, it)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Link"))
                }
                true
            }
            R.id.action_open_in_browser -> {
                val currentUrl = binding.webview.url ?: targetUrl
                currentUrl?.let {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                    startActivity(browserIntent)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
