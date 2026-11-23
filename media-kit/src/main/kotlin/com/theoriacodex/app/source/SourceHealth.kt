package com.theoriacodex.app.source

import androidx.compose.runtime.compositionLocalOf
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.http.HostOutage
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.SiteHealthCheck
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which sources are down, for the source dropdowns and the Series screens, and what tapping one does. [recheck]
 * forgets the source's outage so the search it was picked for tries the network again; [verify] opens the
 * source's browser check over the current screen; [addKey] opens an added site's API key field. [dismissedNotices] are the
 * sources whose outage notice was set aside.
 */
class SourceHealth(
    val issues: Map<SourceKey, HostOutageKind> = emptyMap(),
    val recheck: (SourceKey) -> Unit = {},
    val verify: (SourceKey) -> Unit = {},
    val addKey: (SourceKey) -> Unit = {},
    val dismissedNotices: Set<SourceKey> = emptySet(),
    val dismissNotices: (Collection<SourceKey>) -> Unit = {},
) {
    /** What picking [source] should do first: verify a challenged source, else retry it. */
    fun onPick(source: SourceKey): Boolean {
        val issue = issues[source] ?: return false
        if (issue == HostOutageKind.CHALLENGE) {
            verify(source)
            return true
        }
        recheck(source)
        return false
    }
}

val LocalSourceHealth = compositionLocalOf { SourceHealth() }

/** The action that opens a source's browser check ([SourceHealth.verify]), wherever it is offered. */
const val VERIFY_IN_BROWSER_LABEL = "Verify in browser"

/** The host outages that belong to [source]: its site host, or a subdomain of it such as an API host. */
fun SourceKey.ownsHost(host: String): Boolean {
    val site = siteHost() ?: return false
    return HostOutageTracker.hostBelongsToSite(host, site)
}

/**
 * Active outages by source, matched to the site host of each source (its presentation's address) or of each extension
 * source in [extensionSites], or to a subdomain of it such as an API host.
 */
fun sourceIssues(
    outages: Map<String, HostOutage>,
    sources: Iterable<SourceKey>,
    now: Long,
    extensionSites: Map<SourceKey, String> = emptyMap(),
): Map<SourceKey, HostOutageKind> {
    val active = outages.filterValues { it.untilEpochMs > now }
    if (active.isEmpty()) return emptyMap()
    val sites = sources.mapNotNull { source -> source.siteHost()?.let { source to it } } + extensionSites.toList()
    return sites.mapNotNull { (source, site) ->
        active.entries.firstOrNull { (host, _) -> HostOutageTracker.hostBelongsToSite(host, site) }?.let { source to it.value.kind }
    }.toMap()
}

/** Forgets every outage that belongs to [source]. */
fun HostOutageTracker.clearSource(source: SourceKey) {
    outages.value.keys.filter(source::ownsHost).forEach(::clear)
}

/**
 * The startup check, once per app process: one light request per source's home page, in order and
 * after the app is up, so cached screens are never held back. Real requests trip outages too, so this
 * only gets the red dots in earlier.
 */
fun startSourceHealthCheck(scope: CoroutineScope, check: SiteHealthCheck, sources: () -> List<SourceKey>) {
    if (!startupCheckStarted.compareAndSet(false, true)) return
    scope.launch(Dispatchers.IO) { checkSourcesInBackground(check, sources) }
}

private val startupCheckStarted = AtomicBoolean(false)

/**
 * The sources the startup check may visit. A site with a saved key is left out: the check sends no key, so it
 * would spend an anonymous request and could mark down a site that its keyed requests get through to.
 */
fun startupCheckSources(sources: List<SourceKey>, keyedSiteIds: Set<String>): List<SourceKey> =
    sources.filterNot { it.id in keyedSiteIds }

private suspend fun checkSourcesInBackground(check: SiteHealthCheck, sources: () -> List<SourceKey>) {
    delay(STARTUP_CHECK_DELAY_MS)
    sources().forEach { source ->
        val presentation = source.presentation()
        runCatchingPreservingCancellation {
            check.check(presentation.referer, mapOf("User-Agent" to presentation.userAgent), countChallenges = source.isCustom)
        }
    }
}

private fun SourceKey.siteHost(): String? =
    runCatching { URI(presentation().referer).host }.getOrNull()?.let(HostOutageTracker::normalizeHost)

private const val STARTUP_CHECK_DELAY_MS = 3_000L
