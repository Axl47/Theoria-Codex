package com.theoriacodex.app.source

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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.ui.components.AnnouncedText
import com.theoriacodex.app.ui.components.BrowserPage
import com.theoriacodex.app.ui.components.rememberBrowserState
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.BrowserCookieStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SiteCheckTarget(
    val title: String,
    val url: String,
    val userAgent: String? = null,
    val cookieHost: String? = null,
    val passes: suspend (userAgent: String) -> Boolean,
    val complete: suspend (userAgent: String) -> Unit = {},
    val dismissed: () -> Unit = {},
)

@Stable
class OpenSiteCheck internal constructor(val target: SiteCheckTarget) {
    var stillRefused by mutableStateOf(false)
        internal set
    internal var job: Job? = null
    internal var pending: PageLoad? = null

    internal var refusedCookies: Cookies? = null
}

internal data class PageLoad(val userAgent: String, val letThrough: Boolean)

internal data class Cookies(val header: String?)

@Stable
class SiteChecks(
    private val cookies: BrowserCookieStore,
    private val shown: (SiteCheckTarget?) -> Unit,
    private val targetFor: (SourceKey) -> SiteCheckTarget?,
    coroutineScope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope

    var opened by mutableStateOf<OpenSiteCheck?>(null)
        private set

    fun open(source: SourceKey, then: suspend (userAgent: String) -> Unit = {}) {
        val target = targetFor(source) ?: return
        open(
            target.copy(
                complete = { userAgent ->
                    target.complete(userAgent)
                    then(userAgent)
                },
            ),
        )
    }

    fun open(target: SiteCheckTarget) {
        close()
        shown(target)
        opened = OpenSiteCheck(target)
    }

    fun close() {
        val check = opened ?: return
        check.job?.cancel()
        end(check)
        check.target.dismissed()
    }

    fun pageLoaded(check: OpenSiteCheck, userAgent: String, letThrough: Boolean) {
        if (opened !== check) return
        check.pending = PageLoad(userAgent, letThrough)
        if (check.job?.isActive == true) return
        check.job = scope.launch {
            while (opened === check) {
                val load = check.pending ?: break
                check.pending = null
                if (passes(check, load)) {
                    end(check)
                    launch(io) { runCatching { cookies.flush() } }
                }
            }
        }
    }

    private suspend fun passes(check: OpenSiteCheck, load: PageLoad): Boolean {
        val target = check.target
        val seen = Cookies(withContext(io) { runCatching { cookies.cookieHeader(target.url) }.getOrNull() })
        val outcome = if (seen == check.refusedCookies) {
            Result.success(false)
        } else {
            runCatchingPreservingCancellation {
                withContext(io) { target.passes(load.userAgent) && run { target.complete(load.userAgent); true } }
            }
        }
        val passed = outcome.getOrDefault(false)
        if (!passed) {
            if (outcome.isSuccess) check.refusedCookies = seen
            check.stillRefused = load.letThrough
        }
        return passed
    }

    override fun onCleared() = close()

    private fun end(check: OpenSiteCheck) {
        if (opened !== check) return
        opened = null
        shown(null)
    }
}

@Composable
fun SiteCheckOverlay(checks: SiteChecks) {
    val check = checks.opened ?: return
    Dialog(onDismissRequest = checks::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            key(check) {
                val target = check.target
                val browser = rememberBrowserState(target.url, target.userAgent) { agent, letThrough -> checks.pageLoaded(check, agent, letThrough) }
                BrowserPage(
                    browser,
                    onClose = checks::close,
                    header = {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = checks::close) { Icon(Icons.Default.Close, contentDescription = "Close") }
                            Column {
                                Text(target.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (browser.host != target.title) {
                                    Text(
                                        browser.host,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    footer = {
                        if (check.stillRefused) AnnouncedText(STILL_REFUSED, Modifier.padding(16.dp), error = true)
                    },
                )
            }
        }
    }
}

private const val STILL_REFUSED = "Theoria still can't get through."
