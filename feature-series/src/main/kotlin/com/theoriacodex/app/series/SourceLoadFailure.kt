package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theoriacodex.app.network.AndroidBrowserCookieStore
import com.theoriacodex.app.ui.components.BrowserPage
import com.theoriacodex.app.ui.components.ErrorState
import com.theoriacodex.app.ui.components.rememberBrowserState
import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.sources.http.HostOutageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A source's failed load, where a Series screen shows one: [message] with Retry or, when a browser check stands in the
 * way, "<source> needs a browser check" with Open check, which opens the check and runs [onRetry] once the browser gets
 * through. A check whose site the browser has just shown without one is an ordinary failure instead, since opening it
 * again cannot help. [compact] is the one-line form that sits among other rows; otherwise it is the full-width one.
 * Needs a [BrowserCheckScope] around the screen.
 */
@Composable
internal fun SourceLoadFailure(
    message: String,
    browserCheck: BrowserCheck?,
    sourceName: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val checks = LocalBrowserChecks.current
    val check = browserCheck?.takeUnless(checks::cannotHelp)
    val text = when {
        check != null -> "$sourceName needs a browser check"
        browserCheck != null -> LOAD_FAILED
        else -> message
    }
    val label = if (check == null) RETRY else OPEN_CHECK
    val action = if (check == null) onRetry else { { checks.open(sourceName, check, onRetry) } }
    if (compact) FailureRow(action, modifier, text, label) else ErrorState(text, action, modifier, label)
}

/**
 * Shows the browser checks [content]'s failures open above the whole screen: a failure's row can scroll out of a lazy
 * list, or give way to a spinner while its source loads again, and the check must outlive it.
 */
@Composable
internal fun BrowserCheckScope(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val checks = remember {
        // The WebView writes cookies to disk lazily; a clearance lost to the app being closed would mean another check.
        BrowserChecks(flushCookies = { scope.launch(Dispatchers.IO) { runCatching { AndroidBrowserCookieStore.flush() } } })
    }
    CompositionLocalProvider(LocalBrowserChecks provides checks, content = content)
    checks.opened?.let { opened -> BrowserCheckDialog(opened, onClose = checks::close, onThrough = { checks.through(opened) }) }
}

/** A check open on screen: whose it is, where, and the load to run again once the browser gets through. */
internal class OpenedCheck(val sourceName: String, val check: BrowserCheck, val retry: () -> Unit)

/**
 * The check open on a screen, and the sites whose page the browser showed without a challenge in the last
 * [CANNOT_HELP_MS]. A source still refused after that is refused for something a browser cannot fix, such as how the
 * app's connection looks, so its failure is shown as an ordinary one rather than sending the user round again.
 */
@Stable
internal class BrowserChecks(private val flushCookies: () -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    var opened by mutableStateOf<OpenedCheck?>(null)
        private set
    private val letThrough = mutableStateMapOf<String, Long>()

    fun open(sourceName: String, check: BrowserCheck, retry: () -> Unit) {
        opened = OpenedCheck(sourceName, check, retry)
    }

    fun close() {
        opened = null
    }

    /** The browser got through to [check]'s site: the page closes and the source loads again, once. */
    fun through(check: OpenedCheck) {
        if (opened !== check) return
        HostOutageTracker.hostKey(check.check.url)?.let { letThrough[it] = now() }
        opened = null
        flushCookies()
        check.retry()
    }

    fun cannotHelp(check: BrowserCheck): Boolean {
        val at = HostOutageTracker.hostKey(check.url)?.let(letThrough::get) ?: return false
        return now() - at < CANNOT_HELP_MS
    }

    private companion object {
        const val CANNOT_HELP_MS = 5 * 60 * 1000L
    }
}

private val LocalBrowserChecks = staticCompositionLocalOf<BrowserChecks> { error("A browser check needs a BrowserCheckScope") }

/**
 * [opened]'s page in a full-screen in-app browser that sends the agent the source was refused with, since the
 * clearance cookie it earns is bound to that agent. The host on screen sits under the source's name, so the user
 * always sees where the page has gone. The first page the site lets through, with no HTTP error such as a challenge's
 * 403, closes it through [onThrough]: a check just passed reloads the page that way, and a site that never challenged
 * the browser shows itself that way. Closing it by hand ([onClose]) retries nothing.
 */
@Composable
private fun BrowserCheckDialog(opened: OpenedCheck, onClose: () -> Unit, onThrough: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val browser = rememberBrowserState(opened.check.url, opened.check.userAgent) { _, letThrough -> if (letThrough) onThrough() }
            BrowserPage(browser, onClose = onClose, header = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Column {
                        Text(opened.sourceName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            browser.host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            })
        }
    }
}

private const val RETRY = "Retry"
private const val OPEN_CHECK = "Open check"
