package com.theoriacodex.sources.http

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.custom.isVerificationChallenge
import java.io.IOException

/**
 * One light, untracked request to a site's home page that feeds [tracker]: down answers record an
 * outage, a healthy answer clears one. Never throws and never retries, so it can run in the
 * background without slowing anything. [countChallenges] is for user-added sites, whose gates cover
 * every page; a built-in site may challenge its home page while its API works.
 */
class SiteHealthCheck internal constructor(
    private val tracker: HostOutageTracker,
    private val transport: suspend (HttpRequestSpec) -> SourceByteResponse,
) {
    constructor(tracker: HostOutageTracker) : this(tracker, ::executeHttpRequest)

    suspend fun check(url: String, headers: Map<String, String>, countChallenges: Boolean) {
        val response = runCatchingPreservingCancellation {
            transport(
                HttpRequestSpec(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = null,
                    maxBodyBytes = HOME_PAGE_MAX_BYTES,
                    connectTimeoutMs = TIMEOUT_MS,
                    readTimeoutMs = TIMEOUT_MS,
                    followRedirects = true,
                ),
            )
        }.getOrElse { error ->
            // A page over the size limit still answered, so the site is up.
            if (error is IOException) tracker.recordFailure(url, error) else tracker.clear(hostOf(url))
            return
        }
        val down = when (val kind = HostOutageTracker.outageKindOf(response.statusCode, response.headers)) {
            HostOutageKind.CHALLENGE -> kind.takeIf { countChallenges }
            null -> HostOutageKind.CHALLENGE.takeIf { countChallenges && response.toTextResponse().isVerificationChallenge() }
            else -> kind
        }
        if (down != null) tracker.record(url, down) else tracker.clear(hostOf(url))
    }

    private fun hostOf(url: String): String = HostOutageTracker.hostKey(url).orEmpty()

    private companion object {
        const val HOME_PAGE_MAX_BYTES = 256 * 1024
        const val TIMEOUT_MS = 10_000
    }
}
