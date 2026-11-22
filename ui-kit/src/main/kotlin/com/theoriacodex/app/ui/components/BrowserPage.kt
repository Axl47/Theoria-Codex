package com.theoriacodex.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.key
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
class BrowserState internal constructor(val url: String, private val create: (BrowserState) -> WebView) {
    internal var webView by mutableStateOf<WebView?>(null)
        private set
    internal var progress by mutableIntStateOf(0)
    internal var loadFailed by mutableStateOf(false)

    var available by mutableStateOf(true)
        private set

    /** The host of the page on screen, which is no longer [url]'s once the page has navigated elsewhere. */
    var host by mutableStateOf(hostOf(url))
        internal set

    /** The agent the browser sends, which a clearance cookie it earns is bound to. */
    val userAgent: String? get() = webView?.settings?.userAgentString

    internal fun open() {
        webView = runCatching { create(this) }.getOrNull()
        available = webView != null
    }

    /** Loads [url] again, in a new WebView when the last one's renderer died. */
    fun reload() {
        loadFailed = false
        val view = webView
        if (view == null) open() else view.loadUrl(url)
    }

    /** The renderer died or was killed for memory: the view is dropped, and the page offers to load again. */
    internal fun rendererGone(view: WebView) {
        if (webView !== view) return
        webView = null
        loadFailed = true
    }
}

/**
 * Starts loading [url] in a browser that lives as long as [url] and [userAgent] do, so recomposition never reloads it.
 * [userAgent] replaces the WebView's own when given. [onPageLoaded] gets the browser's agent after each main-frame page
 * that loaded, and whether the site let it through: false when it answered the page with an HTTP error, as a
 * challenge's 403 is.
 */
@Composable
fun rememberBrowserState(
    url: String,
    userAgent: String? = null,
    onPageLoaded: (userAgent: String, letThrough: Boolean) -> Unit = { _, _ -> },
): BrowserState {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val currentOnPageLoaded by rememberUpdatedState(onPageLoaded)
    return remember(url, userAgent) {
        BrowserState(url) { state ->
            createBrowserWebView(context, state, userAgent, backgroundColor) { agent, letThrough -> currentOnPageLoaded(agent, letThrough) }
        }.also(BrowserState::open)
    }
}

/**
 * [state]'s page under [header], with a load bar while it loads and [footer] below. A missing system WebView covers
 * the page with a message and Close ([onClose]); a page that could not load, or whose renderer died, with Try again.
 * The WebView is destroyed when the page leaves the screen.
 */
@Composable
fun BrowserPage(state: BrowserState, onClose: () -> Unit, header: @Composable () -> Unit, footer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        header()
        if (state.webView != null && state.progress < FULL_PROGRESS && !state.loadFailed) {
            LinearProgressIndicator(progress = { state.progress / FULL_PROGRESS.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            state.webView?.let { webView ->
                // A WebView made after its renderer died replaces the old one, which is released and destroyed.
                key(webView) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize(), onRelease = ::release)
                }
            }
            when {
                !state.available -> PageProblem(BROWSER_UNAVAILABLE_MESSAGE) { TextButton(onClick = onClose) { Text("Close") } }
                state.loadFailed -> PageProblem(loadFailedMessage(state.url)) { Button(onClick = state::reload) { Text("Try again") } }
            }
        }
        footer()
    }
}

/**
 * Runs once the view is detached, which destroy() requires. The page's callbacks go first: stopping a load reports
 * its end later, and nothing may touch the view once it is destroyed.
 */
private fun release(view: WebView) {
    view.webViewClient = WebViewClient()
    view.webChromeClient = null
    view.stopLoading()
    view.destroy()
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

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

private fun loadFailedMessage(url: String): String = "Couldn't load ${hostOf(url)}. Check your connection and try again."

/**
 * Makes [state]'s WebView and starts loading its address: it can read neither files nor content providers, goes only
 * to web addresses (another app's scheme is ignored, never handed to that app), and paints [backgroundColor] until the
 * page does, so a dark theme has no white flash. Only main-frame loads are reported: [onPageLoaded] after a page that
 * loaded, the state's `loadFailed` when one could not load at all (an HTTP 403 challenge is a page that loaded).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createBrowserWebView(
    context: Context,
    state: BrowserState,
    userAgent: String?,
    backgroundColor: Int,
    onPageLoaded: (userAgent: String, letThrough: Boolean) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(backgroundColor)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowContentAccess = false
    settings.allowFileAccess = false
    userAgent?.takeIf(String::isNotBlank)?.let { settings.userAgentString = it }
    CookieManager.getInstance().setAcceptCookie(true)
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            state.progress = newProgress
        }
    }
    webViewClient = object : WebViewClient() {
        private var failed = false

        // Set by an HTTP error on the main frame and read when that page finishes, whichever order the page's start
        // and the error arrive in.
        private var refused = false

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in WEB_SCHEMES

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            failed = false
            state.loadFailed = false
            url?.let { state.host = hostOf(it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failed = true
            state.loadFailed = true
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) refused = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val letThrough = !refused
            refused = false
            if (failed || url == null || url == BLANK_PAGE) return
            onPageLoaded(view.settings.userAgentString, letThrough)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            state.rendererGone(view)
            return true
        }
    }
    loadUrl(state.url)
}

private const val BROWSER_UNAVAILABLE_MESSAGE = "The system browser isn't available right now."
private const val FULL_PROGRESS = 100
private const val BLANK_PAGE = "about:blank"
private val WEB_SCHEMES = setOf("http", "https")
