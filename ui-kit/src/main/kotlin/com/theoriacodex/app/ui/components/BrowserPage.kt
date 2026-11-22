package com.theoriacodex.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URI

/**
 * One in-app browser on [url], made by [rememberBrowserState] and shown by [BrowserPage]. [available] is false when
 * the system WebView is missing or updating, which the page shows as a state of its own.
 */
@Stable
class BrowserState internal constructor(val url: String) {
    internal var webView: WebView? = null
    internal var progress by mutableIntStateOf(0)
    internal var loadFailed by mutableStateOf(false)

    val available: Boolean get() = webView != null

    /** The agent the browser sends, which a clearance cookie it earns is bound to. */
    val userAgent: String? get() = webView?.settings?.userAgentString

    fun reload() {
        loadFailed = false
        webView?.loadUrl(url)
    }
}

/**
 * Starts loading [url] in a browser that lives as long as [url] and [userAgent] do, so recomposition never reloads it.
 * [userAgent] replaces the WebView's own when given. [onPageLoaded] gets the browser's agent after each main-frame page
 * that loaded.
 */
@Composable
fun rememberBrowserState(url: String, userAgent: String? = null, onPageLoaded: (userAgent: String) -> Unit = {}): BrowserState {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val currentOnPageLoaded by rememberUpdatedState(onPageLoaded)
    return remember(url, userAgent) {
        BrowserState(url).also { state ->
            state.webView = runCatching {
                createBrowserWebView(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    backgroundColor = backgroundColor,
                    onProgress = { state.progress = it },
                    onLoadFailed = { state.loadFailed = it },
                    onPageLoaded = { currentOnPageLoaded(it) },
                )
            }.getOrNull()
        }
    }
}

/**
 * [state]'s page under [header], with a load bar while it loads and [footer] below. A missing system WebView covers
 * the page with a message and Close ([onClose]); a page that could not load, with Try again. The WebView is destroyed
 * when the page leaves the screen.
 */
@Composable
fun BrowserPage(state: BrowserState, onClose: () -> Unit, header: @Composable () -> Unit, footer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        header()
        if (state.available && state.progress < FULL_PROGRESS && !state.loadFailed) {
            LinearProgressIndicator(progress = { state.progress / FULL_PROGRESS.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            state.webView?.let { webView ->
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    // Runs once the view is detached, which destroy() requires.
                    onRelease = { released ->
                        released.stopLoading()
                        released.destroy()
                    },
                )
            }
            when {
                !state.available -> PageProblem(BROWSER_UNAVAILABLE_MESSAGE) { TextButton(onClick = onClose) { Text("Close") } }
                state.loadFailed -> PageProblem(loadFailedMessage(state.url)) { Button(onClick = state::reload) { Text("Try again") } }
            }
        }
        footer()
    }
}

/** Status text that TalkBack reads when it appears or changes. */
@Composable
fun AnnouncedText(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** Covers the WebView with a message and its way out. */
@Composable
private fun PageProblem(message: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnnouncedText(message, error = true)
        action()
    }
}

private fun loadFailedMessage(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull() ?: url
    return "Couldn't load $host. Check your connection and try again."
}

/**
 * Starts loading [url] in a new WebView that can read neither files nor content providers, painting
 * [backgroundColor] until the page does, so a dark theme has no white flash. Only
 * main-frame loads are reported: [onPageLoaded] with the browser's User-Agent after a page that loaded,
 * [onLoadFailed] true when the main frame could not load (an HTTP 403 challenge is a normal page).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createBrowserWebView(
    context: Context,
    url: String,
    userAgent: String?,
    backgroundColor: Int,
    onProgress: (Int) -> Unit,
    onLoadFailed: (Boolean) -> Unit,
    onPageLoaded: (userAgent: String) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(backgroundColor)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowContentAccess = false
    settings.allowFileAccess = false
    userAgent?.takeIf(String::isNotBlank)?.let { settings.userAgentString = it }
    CookieManager.getInstance().setAcceptCookie(true)
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
    }
    webViewClient = object : WebViewClient() {
        private var failed = false

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            failed = false
            onLoadFailed(false)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failed = true
            onLoadFailed(true)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (failed || url == null || url == BLANK_PAGE) return
            onPageLoaded(view.settings.userAgentString)
        }
    }
    loadUrl(url)
}

private const val BROWSER_UNAVAILABLE_MESSAGE = "The system browser isn't available right now."
private const val FULL_PROGRESS = 100
private const val BLANK_PAGE = "about:blank"
