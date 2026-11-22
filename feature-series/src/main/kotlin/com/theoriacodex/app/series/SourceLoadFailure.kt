package com.theoriacodex.app.series

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.theoriacodex.sources.http.CLEARANCE_COOKIE
import com.theoriacodex.sources.http.cookie
import com.theoriacodex.sources.http.hasNewClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A source's failed load, where a Series screen shows one: [message] with Retry or, when a browser check stands in the
 * way, "<source> needs a browser check" with Open check, which opens the check and runs [onRetry] once it is passed.
 * [compact] is the one-line form that sits among other sources' rows; otherwise it is the full-width one.
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
    var checking by rememberSaveable(browserCheck) { mutableStateOf(false) }
    val text = if (browserCheck == null) message else "$sourceName needs a browser check"
    val label = if (browserCheck == null) RETRY else OPEN_CHECK
    val action = if (browserCheck == null) onRetry else { { checking = true } }
    if (compact) FailureRow(action, modifier, text, label) else ErrorState(text, action, modifier, label)
    if (checking && browserCheck != null) {
        BrowserCheckDialog(sourceName, browserCheck, onClose = { checking = false }) {
            checking = false
            onRetry()
        }
    }
}

/**
 * [check]'s page in a full-screen in-app browser that sends the agent the source was refused with, since the
 * clearance cookie it earns is bound to that agent. Once a clearance other than the one held when it opened appears,
 * it closes itself through [onPassed]; closing it by hand ([onClose]) retries nothing.
 */
@Composable
private fun BrowserCheckDialog(sourceName: String, check: BrowserCheck, onClose: () -> Unit, onPassed: () -> Unit) {
    val currentOnPassed by rememberUpdatedState(onPassed)
    LaunchedEffect(check) {
        val store = AndroidBrowserCookieStore
        // Every store read is guarded and may wait on the WebView's own thread, so none runs on the main one.
        val old = withContext(Dispatchers.IO) { store.cookie(check.url, CLEARANCE_COOKIE) }
        while (!withContext(Dispatchers.IO) { store.hasNewClearance(check.url, old) }) delay(CLEARANCE_POLL_MS)
        currentOnPassed()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            BrowserPage(rememberBrowserState(check.url, check.userAgent), onClose = onClose, header = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text(sourceName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            })
        }
    }
}

private const val RETRY = "Retry"
private const val OPEN_CHECK = "Open check"
private const val CLEARANCE_POLL_MS = 500L
