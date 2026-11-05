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
        if (loadedUrl.contains("intelbase.is", ignoreCase = true)) {
            val queryParam = runCatching {
                android.net.Uri.parse(loadedUrl).getQueryParameter("q").orEmpty()
            }.getOrDefault("")

            val escaped = queryParam.replace("'", "\\'")
            val intelBaseJs = """
                (function() {
                    // 1. Strip any blur filter or Sign-up modal overlay
                    var style = document.createElement('style');
                    style.innerHTML = '* { filter: none !important; -webkit-filter: none !important; backdrop-filter: none !important; } [role="dialog"], [class*="modal" i], [class*="overlay" i] { display: none !important; } body { overflow: auto !important; }';
                    document.head.appendChild(style);

                    // 2. Fill the search input and click ONLY the search icon button next to the input
                    if ('$escaped'.length > 3) {
                        var input = document.querySelector('input[placeholder*="email" i], input[placeholder*="username" i]');
                        if (input && input.value !== '$escaped') {
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            setter.call(input, '$escaped');
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                            setTimeout(function() {
                                var searchBtn = input.nextElementSibling || (input.parentElement && input.parentElement.querySelector('button'));
                                if (searchBtn && !searchBtn.innerText.toLowerCase().includes('sign')) {
                                    searchBtn.click();
                                } else {
                                    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                                    if (input.form) input.form. dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                                }
                            }, 400);
                        }
                    }
                })();
            """.trimIndent()

            mainHandler.postDelayed({
                runCatching { webView.evaluateJavascript(intelBaseJs, null) }
            }, 900L)
        }

        // Capture rendered DOM at 1.8s, 4.2s, and 7.5s so Google Maps Contrib & SPA cards are harvested
        listOf(1_800L, 4_200L, 7_500L).forEach { delayMs ->
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
