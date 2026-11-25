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

class SourceHealth(
    val issues: Map<SourceKey, HostOutageKind> = emptyMap(),
    val passes: Flow<SourceKey> = emptyFlow(),
    val recheck: (SourceKey) -> Unit = {},
    val verify: (SourceKey) -> Unit = {},
    val addKey: (SourceKey) -> Unit = {},
    val dismissedNotices: Set<SourceKey> = emptySet(),
    val dismissNotices: (Collection<SourceKey>) -> Unit = {},
) {
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

const val VERIFY_IN_BROWSER_LABEL = "Verify in browser"

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

fun SourceKey.ownsHost(host: String): Boolean {
    val site = siteHost() ?: return false
    return HostOutageTracker.hostBelongsToSite(host, site)
}

fun sourceIssues(
    outages: Map<String, HostOutage>,
    sources: Iterable<SourceKey>,
    now: Long,
    extensionSites: Map<SourceKey, Set<String>> = emptyMap(),
): Map<SourceKey, HostOutageKind> {
    val active = outages.filterValues { it.untilEpochMs > now }
    if (active.isEmpty()) return emptyMap()
    return sourceSites(sources, extensionSites).mapNotNull { (source, site) ->
        val kinds = active.filterKeys { host -> HostOutageTracker.hostBelongsToSite(host, site) }.values.map { it.kind }
        (kinds.firstOrNull { it == HostOutageKind.RATE_LIMITED } ?: kinds.firstOrNull())?.let { source to it }
    }.toMap()
}

fun sourcesOwning(host: String, sources: Iterable<SourceKey>, extensionSites: Map<SourceKey, Set<String>> = emptyMap()): Set<SourceKey> =
    sourceSites(sources, extensionSites).filter { (_, site) -> HostOutageTracker.hostBelongsToSite(host, site) }.mapTo(LinkedHashSet()) { it.first }

private fun sourceSites(sources: Iterable<SourceKey>, extensionSites: Map<SourceKey, Set<String>>): List<Pair<SourceKey, String>> =
    sources.mapNotNull { source -> source.siteHost()?.let { source to it } } +
        extensionSites.flatMap { (source, hosts) -> hosts.map { source to it } }

fun HostOutageTracker.clearSource(source: SourceKey) {
    outages.value.keys.filter(source::ownsHost).forEach(::clear)
}

fun HostOutageTracker.passSource(source: SourceKey) {
    (outages.value.keys.filter(source::ownsHost) + listOfNotNull(source.siteHost())).toSet().forEach(::pass)
}

fun startSourceHealthCheck(scope: CoroutineScope, check: SiteHealthCheck, sources: () -> List<SourceKey>) {
    if (!startupCheckStarted.compareAndSet(false, true)) return
    scope.launch(Dispatchers.IO) { checkSourcesInBackground(check, sources) }
}

private val startupCheckStarted = AtomicBoolean(false)

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
