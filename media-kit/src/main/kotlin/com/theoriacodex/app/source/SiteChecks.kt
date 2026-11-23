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

/**
 * A site whose browser check the check page runs. [url] opens with [userAgent], or with the browser's own agent when
 * null. [passes] says whether the site's own client now gets through with the browser's agent, and [complete] records
 * a pass; either may throw, which counts as a failed check that the next page tests again. [cookieHost] is a host whose
 * browser cookies the app's own requests may carry while the page is open, so [passes] can test them before the site
 * keeps them; null where the site already keeps them or whoever opened the check grants them. [dismissed] runs when the
 * page is closed without a pass.
 */
data class SiteCheckTarget(
    val title: String,
    val url: String,
    val userAgent: String? = null,
    val cookieHost: String? = null,
    val passes: suspend (userAgent: String) -> Boolean,
    val complete: suspend (userAgent: String) -> Unit = {},
    val dismissed: () -> Unit = {},
)

/** The check on screen; [stillRefused] once the browser got through a page and the site's client still did not. */
@Stable
class OpenSiteCheck internal constructor(val target: SiteCheckTarget) {
    var stillRefused by mutableStateOf(false)
        internal set
    internal var job: Job? = null
    internal var pending: PageLoad? = null

    /** The browser's cookies at the last refused test: a page that brings no new ones has nothing new to test. */
    internal var refusedCookies: Cookies? = null
}

internal data class PageLoad(val userAgent: String, val letThrough: Boolean)

/** Wraps a `Cookie` header so that "none" differs from "not tested yet". */
internal data class Cookies(val header: String?)

/**
 * The one browser check page, for every kind of source and for a site being added: [open] shows it over the whole app
 * and nothing is tapped or solved for the user. After each page the browser finishes, the target's own client is
 * tested, one test at a time, and the first pass completes the check and closes the page; the screen that asked sees
 * its source's issue clear and loads again. [targetFor] makes a source's target, null when it has nothing to check.
 * Owned like a screen's state holder, so a check outlives the Activity being recreated.
 */
@Stable
class SiteChecks(
    private val cookies: BrowserCookieStore,
    /**
     * The check now on screen, or null once none is: while one is, its site gets no hidden browser check, which would
     * expire the clearance the visible one just earned, and its [SiteCheckTarget.cookieHost] gets the browser's cookies.
     */
    private val shown: (SiteCheckTarget?) -> Unit,
    private val targetFor: (SourceKey) -> SiteCheckTarget?,
    coroutineScope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope

    var opened by mutableStateOf<OpenSiteCheck?>(null)
        private set

    /** Opens [source]'s check; [then] runs after its pass is recorded. */
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

    /** Closes the check without completing it. */
    fun close() {
        val check = opened ?: return
        check.job?.cancel()
        end(check)
        check.target.dismissed()
    }

    /** A page of [check]'s finished loading; [letThrough] when the site answered it without an HTTP error. */
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
                    // The WebView writes cookies to disk lazily; a clearance lost to the app being closed would mean another check.
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
            // Only a refusal is remembered: a test that failed on an error says nothing about these cookies.
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

/**
 * The open check, full screen above whatever asked for it: the site's page in a browser that sends the target's
 * agent, under its title and the host on screen, so the user always sees where the page has gone. Closing it by hand
 * completes nothing.
 */
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
