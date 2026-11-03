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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun openInApp(url: String, title: String = "In-App OSINT Viewer") {
        _browserRequest.value = InAppBrowserRequest(url = url, title = title)
    }

    fun closeInApp() {
        _browserRequest.value = null
    }

    /**
     * Loads [url] in an offscreen Chromium WebView with strict cancellable timeouts and
     * a 4-second watchdog so pages that never fire `onPageFinished` cannot hang a scan.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchRenderedHtml(
        url: String,
        waitAfterLoadMs: Long = 1_200L,
        timeoutMs: Long = 8_000L
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
                            "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999"
                        )
                        cookieManager.setCookie(
                            "https://www.google.co.uk",
                            "CONSENT=YES+cb.20240116-07-p0.en-GB+FX+999"
                        )

                        val wv = WebView(ctx)
                        webView = wv
                        cookieManager.setAcceptThirdPartyCookies(wv, true)
                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
                            mainHandler.postDelayed(watchdog, 4_000L)

                            cont.invokeOnCancellation {
                                mainHandler.removeCallbacks(watchdog)
                            }

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
                            cont.invokeOnCancellation {
                                mainHandler.removeCallbacks(jsWatchdog)
                            }

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
