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

@Stable
class BrowserState internal constructor(val url: String, private val create: (BrowserState) -> WebView) {
    internal var webView by mutableStateOf<WebView?>(null)
        private set
    internal var progress by mutableIntStateOf(0)
    internal var loadFailed by mutableStateOf(false)

    var available by mutableStateOf(true)
        private set

    var host by mutableStateOf(hostOf(url))
        internal set

    val userAgent: String? get() = webView?.settings?.userAgentString

    internal fun open() {
        webView = runCatching { create(this) }.getOrNull()
        available = webView != null
    }

    fun reload() {
        loadFailed = false
        val view = webView
        if (view == null) open() else view.loadUrl(url)
    }

    internal fun rendererGone(view: WebView) {
        if (webView !== view) return
        webView = null
        loadFailed = true
    }
}

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

@Composable
fun BrowserPage(state: BrowserState, onClose: () -> Unit, header: @Composable () -> Unit, footer: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        header()
        if (state.webView != null && state.progress < FULL_PROGRESS && !state.loadFailed) {
            LinearProgressIndicator(progress = { state.progress / FULL_PROGRESS.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            state.webView?.let { webView ->
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

private fun release(view: WebView) {
    view.webViewClient = WebViewClient()
    view.webChromeClient = null
    view.stopLoading()
    view.destroy()
}

@Composable
fun AnnouncedText(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

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
