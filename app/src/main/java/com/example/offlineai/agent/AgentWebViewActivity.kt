package com.example.offlineai.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.example.offlineai.LogManager
import com.example.offlineai.R

/**
 * Transparent Activity that hosts a WebView for user-interactive web operations
 * (e.g. login). Uses an Activity instead of overlay window so that the system
 * IME (including secure keyboards on Huawei/EMUI) works correctly.
 *
 * Cookie state is shared with AgentWebView via the global CookieManager.
 */
class AgentWebViewActivity : Activity() {

    companion object {
        private const val TAG = "AgentWebViewActivity"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_HINT = "extra_hint"

        // Static callback for communicating result back to the caller (AgentFloatingWindow)
        @Volatile
        var resultCallback: ((String) -> Unit)? = null

        @JvmStatic
        fun start(context: Context, url: String, hint: String) {
            val intent = Intent(context, AgentWebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HINT, hint)
            }
            context.startActivity(intent)
        }
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_webview)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val hint = intent.getStringExtra(EXTRA_HINT) ?: ""

        LogManager.logI(TAG, "[WEBVIEW_ACTIVITY] onCreate url=$url, hint=$hint")

        // Setup hint
        findViewById<TextView>(R.id.textViewHint)?.text = hint

        // Create WebView
        val wv = WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                @Suppress("DEPRECATION")
                saveFormData = true
                @Suppress("DEPRECATION")
                savePassword = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            }
            // Cookie persistence (shared with AgentWebView via global CookieManager)
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(wv, true)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String) {
                    LogManager.logI(TAG, "[PAGE_LOADED] url=$pageUrl")
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Keep all navigation inside our WebView
                    return false
                }
            }
        }
        webView = wv

        // Attach WebView to container
        val container = findViewById<FrameLayout>(R.id.webViewContainer)
        container.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Load URL
        if (url.isNotBlank()) {
            wv.loadUrl(url)
        }

        // Done button
        findViewById<Button>(R.id.buttonDone)?.setOnClickListener {
            LogManager.logI(TAG, "[WEBVIEW_ACTIVITY] User clicked Done")
            finishWithResult("done")
        }
    }

    private fun finishWithResult(result: String) {
        // Flush cookies so AgentWebView (background) can see them
        CookieManager.getInstance().flush()
        LogManager.logI(TAG, "[WEBVIEW_ACTIVITY] Flushed cookies, finishing with result=$result")

        resultCallback?.invoke(result)
        resultCallback = null
        finish()
    }

    override fun onBackPressed() {
        // If WebView can go back, go back; otherwise finish with empty result
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            LogManager.logI(TAG, "[WEBVIEW_ACTIVITY] Back pressed, finishing")
            finishWithResult("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.logI(TAG, "[WEBVIEW_ACTIVITY] onDestroy")
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        // If callback still pending (e.g. Activity killed), notify with empty
        resultCallback?.invoke("")
        resultCallback = null
    }
}
