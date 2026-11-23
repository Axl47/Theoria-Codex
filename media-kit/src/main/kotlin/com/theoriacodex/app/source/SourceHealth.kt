package com.theoriacodex.app.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Which sources are down, for the source dropdowns and the Series screens, and what tapping one does. [recheck]
 * forgets the source's outage so the search it was picked for tries the network again; [verify] opens the
 * source's browser check over the current screen; [addKey] opens an added site's API key field. [dismissedNotices] are the
 * sources whose outage notice was set aside. [passes] names each source as one of its hosts gets through its browser
 * check ([HostOutageTracker.passes]).
 */
class SourceHealth(
    val issues: Map<SourceKey, HostOutageKind> = emptyMap(),
    val passes: Flow<SourceKey> = emptyFlow(),
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

/**
 * Runs [retry] for each of [sources] that needed browser verification while on screen once its check is passed, here
 * or anywhere else, so the screen that asked loads again by itself. A need that merely expired retries nothing: the
 * site would only challenge again. Returns the sources that need verification now.
 */
@Composable
fun retryWhenVerified(sources: Collection<SourceKey>, retry: (SourceKey) -> Unit): Set<SourceKey> {
    val health = LocalSourceHealth.current
    val shown = sources.toSet()
    val challenged = shown.filterTo(HashSet()) { health.issues[it] == HostOutageKind.CHALLENGE }
    val waiting = remember { HashSet<SourceKey>() }
    SideEffect {
        waiting.retainAll(shown)
        waiting += challenged
    }
    val onRetry by rememberUpdatedState(retry)
    LaunchedEffect(health.passes) {
        health.passes.collect { source -> if (waiting.remove(source)) onRetry(source) }
    }
    return challenged
}

/** The host outages that belong to [source]: its site host, or a subdomain of it such as an API host. */
fun SourceKey.ownsHost(host: String): Boolean {
    val site = siteHost() ?: return false
    return HostOutageTracker.hostBelongsToSite(host, site)
}

/**
 * Active outages by source, matched to the site host of each source (its presentation's address) or to the hosts each
 * extension source in [extensionSites] calls, or to a subdomain of one such as an API host.
 */
fun sourceIssues(
    outages: Map<String, HostOutage>,
    sources: Iterable<SourceKey>,
    now: Long,
    extensionSites: Map<SourceKey, Set<String>> = emptyMap(),
): Map<SourceKey, HostOutageKind> {
    val active = outages.filterValues { it.untilEpochMs > now }
    if (active.isEmpty()) return emptyMap()
    return sourceSites(sources, extensionSites).mapNotNull { (source, site) ->
        active.entries.firstOrNull { (host, _) -> HostOutageTracker.hostBelongsToSite(host, site) }?.let { source to it.value.kind }
    }.toMap()
}

/** The sources [host] belongs to, matched as [sourceIssues] matches outages. */
fun sourcesOwning(host: String, sources: Iterable<SourceKey>, extensionSites: Map<SourceKey, Set<String>> = emptyMap()): Set<SourceKey> =
    sourceSites(sources, extensionSites).filter { (_, site) -> HostOutageTracker.hostBelongsToSite(host, site) }.mapTo(LinkedHashSet()) { it.first }

private fun sourceSites(sources: Iterable<SourceKey>, extensionSites: Map<SourceKey, Set<String>>): List<Pair<SourceKey, String>> =
    sources.mapNotNull { source -> source.siteHost()?.let { source to it } } +
        extensionSites.flatMap { (source, hosts) -> hosts.map { source to it } }

/** Forgets every outage that belongs to [source]. */
fun HostOutageTracker.clearSource(source: SourceKey) {
    outages.value.keys.filter(source::ownsHost).forEach(::clear)
}

/**
 * [source] got through its browser check: passes its site host and every host of its with an outage
 * ([HostOutageTracker.pass]), the site host even when its record has already lapsed, so a screen still waits on none.
 */
fun HostOutageTracker.passSource(source: SourceKey) {
    (outages.value.keys.filter(source::ownsHost) + listOfNotNull(source.siteHost())).toSet().forEach(::pass)
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
