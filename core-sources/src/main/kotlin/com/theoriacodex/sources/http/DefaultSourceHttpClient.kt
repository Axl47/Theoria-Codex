package com.theoriacodex.sources.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class DefaultSourceHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 300L,
    private val shouldRetryStatus: (url: String, statusCode: Int) -> Boolean = ::defaultShouldRetryStatus,
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
            maxBodyBytes = Int.MAX_VALUE,
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
            executeByteRequest(
                url = requestUrl,
                headers = requestHeaders,
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
        return executeWithRetry(url) {
            executeRequest(
                method = "POST",
                url = url,
                headers = normalizedHeaders,
                body = encodedForm.toByteArray(Charsets.UTF_8),
            )
        }
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
        return executeWithRetry(url) {
            executeRequest(
                method = "POST",
                url = url,
                headers = normalizedHeaders,
                body = body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private suspend fun executeWithRetry(
        requestUrl: String,
        block: suspend () -> SourceHttpResponse,
    ): SourceHttpResponse {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                val response = block()
                if (!shouldRetryStatus(requestUrl, response.statusCode) || attempt == maxRetries) {
                    return response
                }
                attempt += 1
                delay(resolveRetryDelayMs(response, attempt, retryBaseDelayMs))
            } catch (error: IOException) {
                lastError = error
                if (attempt == maxRetries) throw error
                attempt += 1
                delay(retryBaseDelayMs * attempt)
            }
        }
        throw IllegalStateException("Unexpected HTTP retry state", lastError)
    }

    private suspend fun executeBytesWithRetry(
        requestUrl: String,
        block: suspend () -> SourceByteResponse,
    ): SourceByteResponse {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                val response = block()
                if (!shouldRetryStatus(requestUrl, response.statusCode) || attempt == maxRetries) {
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
            } catch (error: IOException) {
                lastError = error
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
    ): SourceHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        headers.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(body)
            }
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val bodyText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val headersMap = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { (key, _) -> requireNotNull(key) }

        SourceHttpResponse(
            statusCode = status,
            body = bodyText,
            headers = headersMap,
        )
    }

    private suspend fun executeByteRequest(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
    ): SourceByteResponse = suspendCancellableCoroutine { continuation ->
        val connection = URL(url).openConnection() as HttpURLConnection
        val request = CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = runInterruptible {
                    executeBlockingByteRequest(
                        connection = connection,
                        headers = headers,
                        maxBodyBytes = maxBodyBytes,
                    )
                }
                continuation.resumeWith(Result.success(response))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }
        continuation.invokeOnCancellation {
            request.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                connection.disconnect()
            }
        }
    }

    private fun executeBlockingByteRequest(
        connection: HttpURLConnection,
        headers: Map<String, String>,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val bodyBytes = stream?.use { it.readBoundedBytes(maxBodyBytes) } ?: ByteArray(0)
            val headersMap = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> requireNotNull(key) }

            return SourceByteResponse(
                statusCode = status,
                body = bodyBytes,
                headers = headersMap,
            )
        } finally {
            connection.disconnect()
        }
    }
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

private fun buildUrl(base: String, query: Map<String, String>): String {
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
        return retryAfterSeconds * 1_000L
    }
    return retryBaseDelayMs * attempt
}

private fun isIwaraRequestUrl(requestUrl: String): Boolean {
    val host = runCatching { URL(requestUrl).host.lowercase() }.getOrNull() ?: return false
    return host == "iwara.tv" || host.endsWith(".iwara.tv")
}
