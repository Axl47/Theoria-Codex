package com.theoriacodex.sources.http

import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.delay

/** How many times and how slowly a source request is retried; see [executeWithRetry]. */
internal class SourceRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_RETRY_BASE_DELAY_MS,
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
    while (true) {
        try {
            val response = block()
            val delayMs = if (defaultShouldRetryStatus(requestUrl, response.statusCode)) {
                retryDelayMs(response.headers, attempt + 1, policy.baseDelayMs)
            } else {
                null
            }
            if (delayMs == null || attempt == policy.maxRetries) {
                outages?.settle(requestUrl, response, extraOutage)
                return response
            }
            attempt += 1
            delay(delayMs)
        } catch (error: HostUnavailableException) {
            throw error
        } catch (error: UnknownHostException) {
            outages?.recordFailure(requestUrl, error)
            throw error
        } catch (error: IOException) {
            if (attempt == policy.maxRetries) throw error
            attempt += 1
            delay(policy.baseDelayMs * attempt)
        }
    }
}

/** 5xx answers are retried, and 429s except from Iwara, whose rate limit counts each retry and only lasts longer for it. */
internal fun defaultShouldRetryStatus(requestUrl: String, statusCode: Int): Boolean {
    if (statusCode in 500..599) return true
    if (statusCode != 429) return false
    val host = runCatching { URL(requestUrl).host.lowercase() }.getOrNull() ?: return true
    return host != "iwara.tv" && !host.endsWith(".iwara.tv")
}

/**
 * How long to wait before retry [attempt] of an answer with [headers]: its Retry-After when that is a few
 * seconds, otherwise linear backoff from [baseDelayMs]. Null when Retry-After asks for longer, which is not
 * worth waiting out inside one request (a maintenance page such as Danbooru's sends 30 s).
 */
internal fun retryDelayMs(headers: Map<String, List<String>>, attempt: Int, baseDelayMs: Long): Long? {
    val retryAfterSeconds = headers.header("Retry-After")?.trim()?.toLongOrNull()
    return when {
        retryAfterSeconds == null || retryAfterSeconds <= 0L -> baseDelayMs * attempt
        retryAfterSeconds > MAX_INLINE_RETRY_AFTER_SECONDS -> null
        else -> retryAfterSeconds * 1_000L
    }
}

private const val MAX_INLINE_RETRY_AFTER_SECONDS = 3L
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
