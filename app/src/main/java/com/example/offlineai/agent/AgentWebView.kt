package com.example.offlineai.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.offlineai.LogManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Agent-dedicated background WebView.
 * Created and owned by AgentAccessibilityService.
 * Supports: loadUrl (with page-load wait), getContent (via JS injection), executeJs.
 * All suspend functions use coroutine cancellable continuations.
 */
class AgentWebView(context: Context) {

    companion object {
        private const val TAG = "AgentWebView"
        private const val PAGE_LOAD_TIMEOUT_MS = 15_000L
        private const val CONTENT_EXTRACT_TIMEOUT_MS = 8_000L
        private const val JS_EXECUTE_TIMEOUT_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView = WebView(context.applicationContext).also { wv ->
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        }
        wv.addJavascriptInterface(AgentJsBridge(), "AgentBridge")
    }

    // Pending continuation for page load
    @Volatile private var pageLoadContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    // Pending continuation for content extraction
    @Volatile private var contentContinuation: kotlinx.coroutines.CancellableContinuation<String?>? = null
    // Last loaded URL (for logging)
    @Volatile private var currentUrl: String = ""

    init {
        mainHandler.post {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    LogManager.logI(TAG, "[PAGE_LOADED] url=$url")
                    currentUrl = url
                    pageLoadContinuation?.let { cont ->
                        pageLoadContinuation = null
                        if (cont.isActive) cont.resume(true)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        LogManager.logW(TAG, "[PAGE_ERROR] url=${request.url}, error=${error.description}")
                        pageLoadContinuation?.let { cont ->
                            pageLoadContinuation = null
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Load URL and wait for page to finish loading.
     * @return true if page loaded successfully, false on error or timeout
     */
    suspend fun loadUrl(url: String): Boolean {
        LogManager.logI(TAG, "[LOAD_URL] Loading: $url")
        val loaded = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pageLoadContinuation = cont
                cont.invokeOnCancellation { pageLoadContinuation = null }
                mainHandler.post { webView.loadUrl(url) }
            }
        }
        if (loaded == null) {
            LogManager.logW(TAG, "[LOAD_URL] Timeout loading: $url")
            pageLoadContinuation = null
        }
        return loaded == true
    }

    /**
     * Extract page content via JS injection.
     * Returns JSON string with title, url, text, links, inputs, buttons.
     * Returns null on failure or timeout.
     */
    suspend fun getContent(): String? {
        LogManager.logI(TAG, "[GET_CONTENT] Extracting from: $currentUrl")
        val js = """
            (function() {
                try {
                    var title = document.title || '';
                    var url = window.location.href || '';
                    var bodyText = (document.body ? document.body.innerText : '') || '';
                    var links = Array.from(document.querySelectorAll('a'))
                        .slice(0, 20)
                        .map(function(a) { return {text: (a.innerText||'').trim(), href: a.href}; })
                        .filter(function(l) { return l.text.length > 0; });
                    var buttons = Array.from(document.querySelectorAll('button,input[type=button],input[type=submit]'))
                        .slice(0, 10)
                        .map(function(b) { return (b.innerText || b.value || '').trim(); })
                        .filter(function(t) { return t.length > 0; });
                    var inputs = Array.from(document.querySelectorAll('input[type=text],input[type=search],input[type=email],input[type=password],textarea'))
                        .slice(0, 10)
                        .map(function(i) { return {type: i.type, name: i.name||'', placeholder: i.placeholder||''}; });
                    var result = JSON.stringify({
                        title: title,
                        url: url,
                        text: bodyText.substring(0, 5000),
                        links: links,
                        buttons: buttons,
                        inputs: inputs
                    });
                    AgentBridge.onContentReady(result);
                } catch(e) {
                    AgentBridge.onContentReady('{"error":"' + e.message + '"}');
                }
            })();
        """.trimIndent()

        return withTimeoutOrNull(CONTENT_EXTRACT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                contentContinuation = cont
                cont.invokeOnCancellation { contentContinuation = null }
                mainHandler.post { webView.evaluateJavascript(js, null) }
            }
        }.also {
            if (it == null) {
                LogManager.logW(TAG, "[GET_CONTENT] Timeout extracting content")
                contentContinuation = null
            }
        }
    }

    /**
     * Execute arbitrary JavaScript and return the result string.
     * Returns null on timeout.
     */
    suspend fun executeJs(script: String): String? {
        LogManager.logI(TAG, "[EXEC_JS] script=${script.take(80)}...")
        return withTimeoutOrNull(JS_EXECUTE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    webView.evaluateJavascript(script) { result ->
                        LogManager.logI(TAG, "[EXEC_JS] result=${result?.take(100)}")
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        }.also {
            if (it == null) LogManager.logW(TAG, "[EXEC_JS] Timeout executing JS")
        }
    }

    /**
     * Release WebView resources.
     */
    fun destroy() {
        mainHandler.post {
            webView.stopLoading()
            webView.destroy()
            LogManager.logI(TAG, "[DESTROY] AgentWebView destroyed")
        }
    }

    // JavaScript bridge called by injected JS
    private inner class AgentJsBridge {
        @JavascriptInterface
        fun onContentReady(content: String) {
            LogManager.logI(TAG, "[JS_BRIDGE] Content received: ${content.length} chars")
            contentContinuation?.let { cont ->
                contentContinuation = null
                if (cont.isActive) cont.resume(content)
            }
        }
    }
}
