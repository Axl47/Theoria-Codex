package com.theoriacodex.sources.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DefaultSourceHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxRetries: Int = 2,
    private val retryBaseDelayMs: Long = 300L,
) : SourceHttpClient {
    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        return executeWithRetry {
            executeRequest(
                method = "GET",
                url = buildUrl(url, query),
                headers = headers,
                body = null,
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
        return executeWithRetry {
            executeRequest(
                method = "POST",
                url = url,
                headers = normalizedHeaders,
                body = encodedForm.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private suspend fun executeWithRetry(block: suspend () -> SourceHttpResponse): SourceHttpResponse {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                val response = block()
                if (!shouldRetry(response.statusCode) || attempt == maxRetries) {
                    return response
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt == maxRetries) throw error
            }

            attempt += 1
            delay(retryBaseDelayMs * attempt)
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

    private fun shouldRetry(statusCode: Int): Boolean {
        return statusCode == 429 || statusCode in 500..599
    }
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
}
