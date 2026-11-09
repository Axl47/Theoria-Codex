package com.theoriacodex.sources.http

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.custom.isVerificationChallenge
import java.io.IOException
import okhttp3.OkHttpClient

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
    constructor(tracker: HostOutageTracker, okHttpClient: OkHttpClient = defaultSourceOkHttpClient) :
        this(tracker, { spec -> executeHttpRequest(spec, okHttpClient) })

    suspend fun check(url: String, headers: Map<String, String>, countChallenges: Boolean) {
        val response = runCatchingPreservingCancellation { fetch(url, headers) }.getOrElse { error ->
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

    /**
     * Whether [url] now answers without a challenge or an outage, for a built-in site's browser check.
     * Judged like that site's real requests, by status and headers alone: an ordinary page can mention
     * a challenge in its text. Unlike [check] this leaves the tracker alone, so checking changes nothing
     * until the check is completed. Throws [IOException] when the site can't be reached at all.
     */
    suspend fun clearsChallenge(url: String, headers: Map<String, String>): Boolean {
        val response = runCatchingPreservingCancellation { fetch(url, headers) }.getOrElse { error ->
            if (error is IOException) throw error
            // A page over the size limit still answered.
            return true
        }
        return HostOutageTracker.outageKindOf(response.statusCode, response.headers) == null
    }

    private suspend fun fetch(url: String, headers: Map<String, String>): SourceByteResponse = transport(
        HttpRequestSpec(
            method = "GET",
            url = url,
            headers = headers,
            body = null,
            maxBodyBytes = HOME_PAGE_MAX_BYTES,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        ),
    )

    private fun hostOf(url: String): String = HostOutageTracker.hostKey(url).orEmpty()

    private companion object {
        const val HOME_PAGE_MAX_BYTES = 256 * 1024
        const val READ_TIMEOUT_MS = 10_000
    }
}
