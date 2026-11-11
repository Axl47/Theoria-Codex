package com.theoriacodex.sources.http

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.delay

/** How many times, how slowly, and for which statuses a source request is retried; see [executeWithRetry]. */
internal class SourceRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_RETRY_BASE_DELAY_MS,
    val shouldRetryStatus: (url: String, statusCode: Int) -> Boolean = ::defaultShouldRetryStatus,
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_RETRY_BASE_DELAY_MS = 300L
    }
}

/**
 * Runs [block] and retries transient I/O errors and retryable statuses under [policy], with backoff that
 * honors a short Retry-After. Outages are recorded in [outages] only for the outcome after retries, so a
 * request that recovers leaves nothing behind; a known-down host fails before the first attempt. A
 * non-null [extraOutage] can flag the final answer as an outage too (for example a challenge page).
 */
internal suspend fun executeWithRetry(
    requestUrl: String,
    policy: SourceRetryPolicy,
    outages: HostOutageTracker?,
    extraOutage: (SourceByteResponse) -> HostOutageKind? = { null },
    block: suspend () -> SourceByteResponse,
): SourceByteResponse {
    outages?.ensureAvailable(requestUrl)
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt <= policy.maxRetries) {
        try {
            val response = block()
            // A maintenance page's Retry-After (Danbooru sends 30 s) is not worth waiting out per request.
            val retry = policy.shouldRetryStatus(requestUrl, response.statusCode) && !response.headers.retryAfterTooLong()
            if (!retry || attempt == policy.maxRetries) {
                outages?.settle(requestUrl, response, extraOutage)
                return response
            }
            attempt += 1
            delay(
                resolveRetryDelayMs(
                    response = SourceHttpResponse(
                        statusCode = response.statusCode,
                        body = "",
                        headers = response.headers,
                    ),
                    attempt = attempt,
                    retryBaseDelayMs = policy.baseDelayMs,
                ),
            )
        } catch (error: HostUnavailableException) {
            throw error
        } catch (error: UnknownHostException) {
            outages?.recordFailure(requestUrl, error)
            throw error
        } catch (error: IOException) {
            lastError = error
            outages?.recordFailure(requestUrl, error)
            if (attempt == policy.maxRetries) throw error
            attempt += 1
            delay(policy.baseDelayMs * attempt)
        }
    }
    throw IllegalStateException("Unexpected HTTP retry state", lastError)
}

/** Records the final answer, throwing [HostUnavailableException] when it says the host is down. */
private fun HostOutageTracker.settle(
    requestUrl: String,
    response: SourceByteResponse,
    extraOutage: (SourceByteResponse) -> HostOutageKind?,
) {
    val kind = recordResponse(requestUrl, response.statusCode, response.headers)
        ?: extraOutage(response)?.also { record(requestUrl, it) }
    if (kind != null) throw HostUnavailableException(HostOutageTracker.hostKey(requestUrl).orEmpty(), kind)
    recordAnswer(requestUrl, response.statusCode)
}
