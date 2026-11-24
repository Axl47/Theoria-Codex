package com.theoriacodex.sources.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import com.theoriacodex.domain.encoding.percentEncode
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal const val CONNECT_TIMEOUT_MS = 3_000

class DefaultSourceHttpClient(
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = 20_000,
    maxRetries: Int = SourceRetryPolicy.DEFAULT_MAX_RETRIES,
    retryBaseDelayMs: Long = SourceRetryPolicy.DEFAULT_RETRY_BASE_DELAY_MS,
    private val outages: HostOutageTracker? = null,
    private val userAgentFor: (url: String) -> String? = { null },
    okHttpClient: OkHttpClient = defaultSourceOkHttpClient,
) : SourceHttpClient {
    private val retryPolicy = SourceRetryPolicy(maxRetries, retryBaseDelayMs)
    private val transport = httpTransport(okHttpClient, connectTimeoutMs, readTimeoutMs, followRedirects = true)

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
        return executeWithRetry(requestUrl, retryPolicy, outages) {
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
        val response = executeWithRetry(url, retryPolicy, outages) {
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
        val response = executeWithRetry(url, retryPolicy, outages) {
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

    private suspend fun executeRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBodyBytes: Int,
    ): SourceByteResponse = transport(
        HttpRequestSpec(
            method = method,
            url = url,
            headers = userAgentFor(url)?.let { agent ->
                headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) } + ("User-Agent" to agent)
            } ?: headers,
            body = body,
            maxBodyBytes = maxBodyBytes,
        ),
    )
}

fun unqueuedSourceDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequests = MAX_CONCURRENT_SOURCE_REQUESTS
    maxRequestsPerHost = MAX_CONCURRENT_SOURCE_REQUESTS
}

private const val MAX_CONCURRENT_SOURCE_REQUESTS = 128

internal val defaultSourceOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(unqueuedSourceDispatcher())
        .addInterceptor(unexpectedFailuresAsIoExceptions)
        .build()
}

internal class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val maxBodyBytes: Int,
)

internal fun httpTransport(
    okHttpClient: OkHttpClient,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    followRedirects: Boolean,
): suspend (HttpRequestSpec) -> SourceByteResponse {
    val client = okHttpClient.newBuilder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(followRedirects)
        .followSslRedirects(false)
        .build()
    return { spec -> client.newCall(spec.toRequest()).await { response -> response.toSourceResponse(spec.maxBodyBytes) } }
}

private fun HttpRequestSpec.toRequest(): Request = try {
    val builder = Request.Builder().url(requireNotNull(url.toHttpUrlOrNull()) { "Malformed URL" })
    headers.forEach { (name, value) -> builder.header(name, value) }
    if (headers.keys.none { it.equals(USER_AGENT_HEADER, ignoreCase = true) }) {
        System.getProperty(PLATFORM_AGENT_PROPERTY)?.let { builder.header(USER_AGENT_HEADER, it) }
    }
    builder.method(method, body?.toRequestBody()).build()
} catch (error: IllegalArgumentException) {
    throw IOException("Invalid request", error)
}

private fun Response.toSourceResponse(maxBodyBytes: Int): SourceByteResponse {
    if (body.contentLength() > maxBodyBytes.toLong()) throw SourceHttpBodyTooLargeException(maxBodyBytes)
    return SourceByteResponse(
        statusCode = code,
        body = body.byteStream().readBoundedBytes(maxBodyBytes),
        headers = headers.toCaseInsensitiveMap(),
    )
}

private fun Headers.toCaseInsensitiveMap(): Map<String, List<String>> =
    TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER).also { map ->
        forEach { (name, value) -> map.getOrPut(name) { mutableListOf() } += value }
    }

private const val PLATFORM_AGENT_PROPERTY = "http.agent"
private const val USER_AGENT_HEADER = "User-Agent"

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
        "${key.percentEncode()}=${value.percentEncode()}"
    }
}
