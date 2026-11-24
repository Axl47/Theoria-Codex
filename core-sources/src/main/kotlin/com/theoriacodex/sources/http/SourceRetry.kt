package com.theoriacodex.sources.http

import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.delay

internal class SourceRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_RETRY_BASE_DELAY_MS,
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_RETRY_BASE_DELAY_MS = 300L
    }
}

internal suspend fun executeWithRetry(
    requestUrl: String,
    policy: SourceRetryPolicy,
    outages: HostOutageTracker?,
    extraOutage: (SourceByteResponse) -> HostOutageKind? = { null },
    block: suspend () -> SourceByteResponse,
): SourceByteResponse {
    val host = HostOutageTracker.hostKey(requestUrl) ?: "unknown host"
    return try {
        retrying(requestUrl, policy, outages, extraOutage, block).also { response ->
            if (response.statusCode >= 400) Diagnostics.report("http", "$host: HTTP ${response.statusCode}")
        }
    } catch (error: IOException) {
        Diagnostics.report("http", "$host: request failed", error)
        throw error
    }
}

private suspend fun retrying(
    requestUrl: String,
    policy: SourceRetryPolicy,
    outages: HostOutageTracker?,
    extraOutage: (SourceByteResponse) -> HostOutageKind?,
    block: suspend () -> SourceByteResponse,
): SourceByteResponse {
    outages?.ensureAvailable(requestUrl)
    var attempt = 0
    while (true) {
        try {
            val response = block()
            val delayMs = policy.delayBeforeRetry(response, attempt)
            if (delayMs == null) {
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

private fun SourceRetryPolicy.delayBeforeRetry(response: SourceByteResponse, attempt: Int): Long? {
    if (attempt == maxRetries || isCloudflareChallenge(response.statusCode, response.headers::header)) return null
    if (response.statusCode !in 500..599) return null
    return retryDelayMs(response.headers, attempt + 1, baseDelayMs)
}

internal fun retryDelayMs(headers: Map<String, List<String>>, attempt: Int, baseDelayMs: Long): Long? {
    val retryAfterSeconds = headers.header("Retry-After")?.trim()?.toLongOrNull()
    return when {
        retryAfterSeconds == null || retryAfterSeconds <= 0L -> baseDelayMs * attempt
        retryAfterSeconds > MAX_INLINE_RETRY_AFTER_SECONDS -> null
        else -> retryAfterSeconds * 1_000L
    }
}

private const val MAX_INLINE_RETRY_AFTER_SECONDS = 3L
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
