package com.theoriacodex.sources.http

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.common.sourceCall
import com.theoriacodex.sources.custom.isVerificationChallenge
import java.io.IOException
import okhttp3.OkHttpClient

class SiteHealthCheck internal constructor(
    private val tracker: HostOutageTracker,
    private val transport: suspend (HttpRequestSpec) -> SourceByteResponse,
) {
    constructor(tracker: HostOutageTracker, okHttpClient: OkHttpClient = defaultSourceOkHttpClient) :
        this(tracker, httpTransport(okHttpClient, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, followRedirects = true))

    suspend fun check(url: String, headers: Map<String, String>, countChallenges: Boolean): Unit = sourceCall {
        val response = runCatchingPreservingCancellation { fetch(url, headers) }.getOrElse { error ->
            if (error is IOException) tracker.recordFailure(url, error) else tracker.clear(hostOf(url))
            return@sourceCall
        }
        val down = when (val kind = HostOutageTracker.outageKindOf(response.statusCode, response.headers)) {
            HostOutageKind.CHALLENGE -> kind.takeIf { countChallenges }
            null -> HostOutageKind.CHALLENGE.takeIf { countChallenges && response.toTextResponse().isVerificationChallenge() }
            else -> kind
        }
        if (down != null) tracker.record(url, down) else tracker.clear(hostOf(url))
    }

    suspend fun clearsChallenge(url: String, headers: Map<String, String>): Boolean = sourceCall {
        val response = runCatchingPreservingCancellation { fetch(url, headers) }.getOrElse { error ->
            if (error is IOException) throw error
            return@sourceCall true
        }
        HostOutageTracker.outageKindOf(response.statusCode, response.headers) == null
    }

    private suspend fun fetch(url: String, headers: Map<String, String>): SourceByteResponse = transport(
        HttpRequestSpec(
            method = "GET",
            url = url,
            headers = headers,
            body = null,
            maxBodyBytes = HOME_PAGE_MAX_BYTES,
        ),
    )

    private fun hostOf(url: String): String = HostOutageTracker.hostKey(url).orEmpty()

    private companion object {
        const val HOME_PAGE_MAX_BYTES = 256 * 1024
        const val READ_TIMEOUT_MS = 10_000
    }
}
