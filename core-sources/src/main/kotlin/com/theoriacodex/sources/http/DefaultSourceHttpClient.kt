package com.theoriacodex.sources.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.delay

/**
 * Android gives each of a host's addresses its own connect timeout and tries IPv6 first, so on a network
 * whose IPv6 silently drops packets every dual-stack site waits this long before trying IPv4.
 */
internal const val CONNECT_TIMEOUT_MS = 3_000

class DefaultSourceHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = 20_000,
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 300L,
    private val shouldRetryStatus: (url: String, statusCode: Int) -> Boolean = ::defaultShouldRetryStatus,
    /** When set, known-down hosts fail at once and outage answers throw [HostUnavailableException]. */
    private val outages: HostOutageTracker? = null,
) : SourceHttpClient {
    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        val response = getBytes(
            url = url,
            query = query,
            headers = headers,
            maxBodyBytes = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES,
        )
        return SourceHttpResponse(
            statusCode = response.statusCode,
            body = response.body.toString(Charsets.UTF_8),
            headers = response.headers,
        )
    }

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        val requestUrl = buildUrl(url, query)
        val requestHeaders = headers.withByteRange(range)
        return executeBytesWithRetry(requestUrl) {
            executeRequest(
                method = "GET",
                url = requestUrl,
                headers = requestHeaders,
                body = null,
                maxBodyBytes = maxBodyBytes,
            )
        }
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        val encodedForm = encodeForm(form)
        val normalizedHeaders = if ("Content-Type" in headers) {
            headers
        } else {
            headers + ("Content-Type" to "application/x-www-form-urlencoded")
        }
        val response = executeBytesWithRetry(url) {
            executeRequest(
                method = "POST",
                url = url,
                headers = normalizedHeaders,
                body = encodedForm.toByteArray(Charsets.UTF_8),
                maxBodyBytes = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES,
            )
        }
        return response.toTextResponse()
    }

    override suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        val normalizedHeaders = if ("Content-Type" in headers) {
            headers
        } else {
            headers + ("Content-Type" to "application/json")
        }
        val response = executeBytesWithRetry(url) {
            executeRequest(
                method = "POST",
                url = url,
                headers = normalizedHeaders,
                body = body.toByteArray(Charsets.UTF_8),
                maxBodyBytes = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES,
            )
        }
        return response.toTextResponse()
    }

    private suspend fun executeBytesWithRetry(
        requestUrl: String,
        block: suspend () -> SourceByteResponse,
    ): SourceByteResponse {
        outages?.ensureAvailable(requestUrl)
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                val response = block()
                // A maintenance page's Retry-After (Danbooru sends 30 s) is not worth waiting out per request.
                val retry = shouldRetryStatus(requestUrl, response.statusCode) && !response.headers.retryAfterTooLong()
                if (!retry || attempt == maxRetries) {
                    outages?.recordResponse(requestUrl, response.statusCode, response.headers)
                        ?.let { kind -> throw HostUnavailableException(HostOutageTracker.hostKey(requestUrl).orEmpty(), kind) }
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
                        retryBaseDelayMs = retryBaseDelayMs,
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
                if (attempt == maxRetries) throw error
                attempt += 1
                delay(retryBaseDelayMs * attempt)
            }
        }
        throw IllegalStateException("Unexpected HTTP retry state", lastError)
    }

    private suspend fun executeRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBodyBytes: Int,
    ): SourceByteResponse = executeHttpRequest(
        HttpRequestSpec(
            method = method,
            url = url,
            headers = headers,
            body = body,
            maxBodyBytes = maxBodyBytes,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            followRedirects = true,
        ),
    )
}

/** One HTTP exchange as [executeHttpRequest] performs it; shared by every [SourceHttpClient]. */
internal class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val maxBodyBytes: Int,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    /** False returns a 3xx as-is, so the caller can vet `Location` before following it. */
    val followRedirects: Boolean,
)

/** Performs one cancellable request with a bounded body; no retries. */
internal suspend fun executeHttpRequest(spec: HttpRequestSpec): SourceByteResponse {
    val connection = URL(spec.url).openConnection() as HttpURLConnection
    return executeCancellableHttpConnection(connection) { executeBlockingRequest(connection, spec) }
}

private fun executeBlockingRequest(connection: HttpURLConnection, spec: HttpRequestSpec): SourceByteResponse {
    connection.requestMethod = spec.method
    connection.connectTimeout = spec.connectTimeoutMs
    connection.readTimeout = spec.readTimeoutMs
    connection.instanceFollowRedirects = spec.followRedirects
    connection.useCaches = false
    spec.headers.forEach { (key, value) ->
        connection.setRequestProperty(key, value)
    }

    if (spec.body != null) {
        connection.doOutput = true
        connection.outputStream.use { output ->
            output.write(spec.body)
        }
    }

    val status = connection.responseCode
    val contentLength = connection.contentLengthLong
    if (contentLength > spec.maxBodyBytes.toLong()) {
        throw SourceHttpBodyTooLargeException(spec.maxBodyBytes)
    }
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    val bodyBytes = stream?.use { it.readBoundedBytes(spec.maxBodyBytes) } ?: ByteArray(0)
    val headersMap = connection.headerFields
        .filterKeys { it != null }
        .mapKeys { (key, _) -> requireNotNull(key) }

    return SourceByteResponse(
        statusCode = status,
        body = bodyBytes,
        headers = headersMap,
    )
}

internal fun SourceByteResponse.toTextResponse(): SourceHttpResponse {
    return SourceHttpResponse(
        statusCode = statusCode,
        body = body.toString(Charsets.UTF_8),
        headers = headers,
    )
}

private fun InputStream.readBoundedBytes(maxBodyBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total > maxBodyBytes - read) {
            throw SourceHttpBodyTooLargeException(maxBodyBytes)
        }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

internal fun buildUrl(base: String, query: Map<String, String>): String {
    if (query.isEmpty()) return base
    val separator = if ("?" in base) "&" else "?"
    return base + separator + encodeForm(query)
}

private fun encodeForm(values: Map<String, String>): String {
    return values.entries.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
        // Query-component spaces should be encoded as %20 for broader API compatibility.
        .replace("+", "%20")
}

internal fun defaultShouldRetryStatus(requestUrl: String, statusCode: Int): Boolean {
    if (statusCode in 500..599) return true
    if (statusCode != 429) return false
    return !isIwaraRequestUrl(requestUrl)
}

internal fun resolveRetryDelayMs(
    response: SourceHttpResponse,
    attempt: Int,
    retryBaseDelayMs: Long,
): Long {
    val retryAfterSeconds = response.headers.entries
        .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.toLongOrNull()
    if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
        return retryAfterSeconds
            .coerceAtMost(MAX_SERVER_RETRY_DELAY_MS / 1_000L) * 1_000L
    }
    return retryBaseDelayMs * attempt
}

internal const val MAX_SERVER_RETRY_DELAY_MS = 30_000L

private fun isIwaraRequestUrl(requestUrl: String): Boolean {
    val host = runCatching { URL(requestUrl).host.lowercase() }.getOrNull() ?: return false
    return host == "iwara.tv" || host.endsWith(".iwara.tv")
}
