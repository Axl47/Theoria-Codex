package com.theoriacodex.mihonhost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.theoriacodex.domain.adapter.BrowserCheck
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request

/**
 * A browser check stood in front of [check]'s address and could not be passed out of sight; only a visible browser,
 * with the same agent, can pass it.
 */
class BrowserCheckException(val check: BrowserCheck) : IOException("Needs a browser check")

/** Where `CloudflareInterceptor` passes a check out of sight; a port so its decisions are tested without a WebView. */
fun interface ChallengeBrowser {
    /** Loads [request] until [passed] answers true or the time runs out, and says whether it did. Never throws for a missing browser. */
    fun pass(request: Request, passed: () -> Boolean): Boolean
}

/**
 * Mihon's way through a check: a WebView no one sees, with JavaScript and DOM storage on and the request's agent and
 * headers, loads the challenged page, whose script then runs the check. [passed] is polled from the request's thread
 * for up to [TIMEOUT_MS]; the WebView is made and destroyed on the main thread. No main thread (plain unit tests), a
 * call from the main thread (it would wait on itself), and a missing or updating WebView each count as a failed pass.
 */
internal class WebViewChallengeBrowser(private val context: Context) : ChallengeBrowser {
    override fun pass(request: Request, passed: () -> Boolean): Boolean {
        val main = Looper.getMainLooper() ?: return false
        if (main.isCurrentThread) return false
        val handler = Handler(main)
        val unavailable = CountDownLatch(1)
        var webView: WebView? = null
        handler.post {
            webView = runCatching { open(request) }.getOrNull()
            if (webView == null) unavailable.countDown()
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (passed()) return true
                if (unavailable.await(POLL_MS, TimeUnit.MILLISECONDS)) return false
            }
            return passed()
        } finally {
            // Posted after the one that made it, so it always finds the WebView that was made.
            handler.post { webView?.run { stopLoading(); destroy() } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(request: Request): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        request.header("User-Agent")?.takeIf(String::isNotBlank)?.let { settings.userAgentString = it }
        CookieManager.getInstance().setAcceptCookie(true)
        // Keeps the check's redirects inside this WebView instead of handing them to another app.
        webViewClient = WebViewClient()
        loadUrl(request.url.toString(), request.headers.toMap())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
    }
}
