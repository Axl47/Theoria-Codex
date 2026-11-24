package com.theoriacodex.app.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.theoriacodex.sources.http.ChallengeBrowser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request

internal fun browserHeaders(request: Request): Map<String, String> =
    listOf("Accept-Language", "Referer").mapNotNull { name -> request.header(name)?.let { name to it } }.toMap()

class WebViewChallengeBrowser(private val context: Context) : ChallengeBrowser {
    override fun mayWait(): Boolean = Looper.getMainLooper()?.isCurrentThread != true

    override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean {
        val handler = Handler(Looper.getMainLooper() ?: return false)
        val ended = CountDownLatch(1)
        var webView: WebView? = null
        handler.post {
            webView = runCatching { open(request, ended::countDown) }.getOrNull()
            if (webView == null) ended.countDown()
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline && !cancelled()) {
                if (passed()) return true
                if (ended.await(POLL_MS, TimeUnit.MILLISECONDS)) break
            }
            return passed()
        } finally {
            handler.post {
                webView?.run {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(request: Request, onEnded: () -> Unit): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        request.header("User-Agent")?.takeIf(String::isNotBlank)?.let { settings.userAgentString = it }
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme !in WEB_SCHEMES

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onEnded()
                return true
            }
        }
        loadUrl(request.url.toString(), browserHeaders(request))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
        val WEB_SCHEMES = setOf("http", "https")
    }
}
