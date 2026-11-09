package com.theoriacodex.app.settings.sites

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.app.settings.SettingsHeader

/**
 * Shows [SiteVerification.host]'s home page so the person can pass its browser challenge themselves.
 * Nothing is clicked or solved for them. Each finished page load reports the browser's cookies and
 * User-Agent (clearance cookies are usually bound to the agent that earned them), the app checks
 * whether its own requests now get through, and the page closes itself once they do after a refusal.
 * Done asks for that check at once. The WebView lives as long as the host, so recomposition never
 * reloads it; a missing system WebView and a failed load are states of this page, not separate routes.
 */
@Composable
internal fun SiteVerificationPage(verification: SiteVerification, siteName: String, onAction: (CustomSitesAction) -> Unit) {
    val host = verification.host
    BackHandler { onAction(CustomSitesAction.CancelVerification) }
    val context = LocalContext.current
    val bridge = (context.applicationContext as TheoriaApplication).siteCookieBridge
    val currentOnAction by rememberUpdatedState(onAction)
    var progress by remember(host) { mutableIntStateOf(0) }
    var loadFailed by remember(host) { mutableStateOf(false) }
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    val webView = remember(host) {
        runCatching {
            createVerificationWebView(
                context = context,
                url = "https://$host/",
                backgroundColor = backgroundColor,
                onProgress = { progress = it },
                onLoadFailed = { loadFailed = it },
                onPageLoaded = { userAgent ->
                    currentOnAction(CustomSitesAction.VerificationPageLoaded(userAgent, bridge.cookiesFor("https://$host/")))
                },
            )
        }.getOrNull()
    }
    fun reload() {
        loadFailed = false
        webView?.loadUrl("https://$host/")
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            SettingsHeader("Verify", onBack = { onAction(CustomSitesAction.CancelVerification) }, parents = SITES_PATH + siteName) {
                IconButton(onClick = ::reload, enabled = webView != null) { Icon(Icons.Default.Refresh, "Reload") }
            }
        }
        if (webView != null && progress < FULL_PROGRESS && !loadFailed) {
            LinearProgressIndicator(progress = { progress / FULL_PROGRESS.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            if (webView != null) {
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
                webView == null -> PageProblem(BROWSER_UNAVAILABLE_MESSAGE) {
                    TextButton(onClick = { onAction(CustomSitesAction.CancelVerification) }) { Text("Close") }
                }
                loadFailed -> PageProblem(verificationLoadFailedMessage(host)) {
                    Button(onClick = ::reload) { Text("Try again") }
                }
            }
        }
        VerificationStatusBar(verification, browserReady = webView != null) {
            webView?.let { onAction(CustomSitesAction.ConfirmVerification(it.settings.userAgentString)) }
        }
    }
}

/** The instruction, the last answer or "Checking…", above Done. */
@Composable
private fun VerificationStatusBar(verification: SiteVerification, browserReady: Boolean, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (verification.checking) {
            Progress("Checking…")
        } else {
            AnnouncedText(verification.message ?: VERIFICATION_INSTRUCTION, error = verification.messageIsError)
        }
        Button(onClick = onDone, enabled = browserReady && !verification.checking) { Text("Done") }
    }
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

/**
 * Starts loading [url] in a new WebView that can read neither files nor content providers, painting
 * [backgroundColor] until the page does, so a dark theme has no white flash. Only
 * main-frame loads are reported: [onPageLoaded] with the browser's User-Agent after a page that loaded,
 * [onLoadFailed] true when the main frame could not load (an HTTP 403 challenge is a normal page).
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createVerificationWebView(
    context: Context,
    url: String,
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

private const val FULL_PROGRESS = 100
private const val BLANK_PAGE = "about:blank"
