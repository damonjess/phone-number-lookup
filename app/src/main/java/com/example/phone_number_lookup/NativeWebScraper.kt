package com.example.phone_number_lookup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

data class InAppBrowserRequest(
    val url: String,
    val title: String = "In-App OSINT Viewer"
)

object NativeWebScraper {

    @Volatile
    private var appContext: Context? = null

    private val scrapeMutex = Mutex()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val _browserRequest = MutableStateFlow<InAppBrowserRequest?>(null)
    val browserRequest: StateFlow<InAppBrowserRequest?> = _browserRequest.asStateFlow()

    // Live callback invoked whenever the user views a page in the In-App Browser so Google results/reviews are captured automatically
    private val _lastCapturedHtml = MutableStateFlow<Pair<String, String>?>(null)
    val lastCapturedHtml: StateFlow<Pair<String, String>?> = _lastCapturedHtml.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun openInApp(url: String, title: String = "In-App OSINT Viewer") {
        _browserRequest.value = InAppBrowserRequest(url = url, title = title)
    }

    fun closeInApp() {
        _browserRequest.value = null
    }

    fun onInAppPageHtmlCaptured(url: String, html: String) {
        if (html.length > 500) {
            _lastCapturedHtml.value = url to html
        }
    }

    /**
     * Attaches auto-fill for OSINT portals (IntelBase/Epieos) and multi-stage DOM harvesting
     * so async React/Google Maps results are captured after they finish rendering.
     */
    fun attachInteractiveHarvester(webView: WebView, loadedUrl: String) {
        // 1. If IntelBase was opened with ?q=..., auto-fill the React input and click submit!
        if (loadedUrl.contains("intelbase.is", ignoreCase = true) && loadedUrl.contains("q=")) {
            val queryParam = android.net.Uri.parse(loadedUrl).getQueryParameter("q").orEmpty()
            if (queryParam.isNotBlank()) {
                val escaped = queryParam.replace("'", "\\'")
                val autoFillJs = """
                    (function() {
                        var input = document.querySelector('input[placeholder*="email" i], input[type="text"], input[type="search"]');
                        if (input && !input.value) {
                            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            nativeInputValueSetter.call(input, '$escaped');
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                            setTimeout(function() {
                                var btn = input.parentElement ? input.parentElement.querySelector('button') : document.querySelector('button[type="submit"]');
                                if (btn) btn.click();
                                else input.form && input.form.submit();
                            }, 350);
                        }
                    })();
                """.trimIndent()
                mainHandler.postDelayed({
                    runCatching { webView.evaluateJavascript(autoFillJs, null) }
                }, 800L)
            }
        }

        // 2. Capture the rendered DOM at 1.5s, 4.0s, and 7.0s so async SPA cards are harvested
        listOf(1_500L, 4_000L, 7_000L).forEach { delayMs ->
            mainHandler.postDelayed({
                runCatching {
                    webView.evaluateJavascript(
                        "(function() { return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                    ) { raw ->
                        decodeJsString(raw)?.let { html ->
                            onInAppPageHtmlCaptured(webView.url ?: loadedUrl, html)
                        }
                    }
                }
            }, delayMs)
        }
    }

    /**
     * Loads [url] in an offscreen Chromium WebView using a Desktop Chrome User-Agent
     * (so Google serves standard `<h3>` result blocks) with a strict 4.5s watchdog.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchRenderedHtml(
        url: String,
        waitAfterLoadMs: Long = 1_500L,
        timeoutMs: Long = 9_000L
    ): String? {
        val ctx = appContext ?: return null
        return withTimeoutOrNull(timeoutMs) {
            scrapeMutex.withLock {
                withContext(Dispatchers.Main) {
                    var webView: WebView? = null
                    try {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setCookie(
                            "https://www.google.com",
                            "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999; SOCS=CAISHAgBEhJnd3NfMjAyNDAxMTYtMF9SQzIaAmVuIAEaBgiA_LyuBg"
                        )
                        cookieManager.setCookie(
                            "https://www.google.co.uk",
                            "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999; SOCS=CAISHAgBEhJnd3NfMjAyNDAxMTYtMF9SQzIaAmVuIAEaBgiA_LyuBg"
                        )

                        val wv = WebView(ctx)
                        webView = wv
                        cookieManager.setAcceptThirdPartyCookies(wv, true)
                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadsImagesAutomatically = true
                            blockNetworkImage = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        }

                        suspendCancellableCoroutine<Unit> { cont ->
                            var completed = false
                            val watchdog = Runnable {
                                if (!completed && cont.isActive) {
                                    completed = true
                                    runCatching { wv.stopLoading() }
                                    cont.resume(Unit)
                                }
                            }
                            mainHandler.postDelayed(watchdog, 4_500L)
                            cont.invokeOnCancellation { mainHandler.removeCallbacks(watchdog) }

                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false

                                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                    if (!completed && cont.isActive) {
                                        completed = true
                                        mainHandler.removeCallbacks(watchdog)
                                        cont.resume(Unit)
                                    }
                                }
                            }
                            wv.loadUrl(url)
                        }

                        delay(waitAfterLoadMs)

                        val rawJsResult = suspendCancellableCoroutine<String?> { cont ->
                            var completed = false
                            val jsWatchdog = Runnable {
                                if (!completed && cont.isActive) {
                                    completed = true
                                    cont.resume(null)
                                }
                            }
                            mainHandler.postDelayed(jsWatchdog, 2_000L)
                            cont.invokeOnCancellation { mainHandler.removeCallbacks(jsWatchdog) }

                            wv.evaluateJavascript(
                                "(function() { return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                            ) { value ->
                                if (!completed && cont.isActive) {
                                    completed = true
                                    mainHandler.removeCallbacks(jsWatchdog)
                                    cont.resume(value)
                                }
                            }
                        }
                        decodeJsString(rawJsResult)
                    } catch (_: Exception) {
                        null
                    } finally {
                        runCatching {
                            webView?.stopLoading()
                            webView?.destroy()
                        }
                    }
                }
            }
        }
    }

    internal fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            JSONTokener(raw).nextValue()?.toString()
        }.getOrNull()
    }
}
