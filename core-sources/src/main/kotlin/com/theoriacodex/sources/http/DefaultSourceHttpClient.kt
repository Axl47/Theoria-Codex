package com.theoriacodex.sources.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** How long one connection attempt may take. OkHttp races a host's addresses, so a dead one costs far less than this. */
internal const val CONNECT_TIMEOUT_MS = 3_000

class DefaultSourceHttpClient(
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = 20_000,
    maxRetries: Int = SourceRetryPolicy.DEFAULT_MAX_RETRIES,
    retryBaseDelayMs: Long = SourceRetryPolicy.DEFAULT_RETRY_BASE_DELAY_MS,
    /** When set, known-down hosts fail at once and outage answers throw [HostUnavailableException]. */
    private val outages: HostOutageTracker? = null,
    /** The User-Agent a site's browser clearance is bound to, replacing the request's own; null keeps it. */
    private val userAgentFor: (url: String) -> String? = { null },
    /** The app passes its shared client, so these calls reuse the process's connections and carry its cookies. */
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

/**
 * A dispatcher that never queues, for clients that make provider requests: whole pages of metadata arrive in bursts
 * to one host, and queueing them behind OkHttp's default of five per host would serialize what HTTP/2 can multiplex.
 */
fun unqueuedSourceDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequests = MAX_CONCURRENT_SOURCE_REQUESTS
    maxRequestsPerHost = MAX_CONCURRENT_SOURCE_REQUESTS
}

private const val MAX_CONCURRENT_SOURCE_REQUESTS = 128

/** For callers outside the app: tests and the provider health CLI. */
internal val defaultSourceOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(unqueuedSourceDispatcher())
        .addInterceptor(unexpectedFailuresAsIoExceptions)
        .build()
}

/** One HTTP exchange as an [httpTransport] performs it; shared by every [SourceHttpClient]. */
internal class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val maxBodyBytes: Int,
)

/**
 * Sends each request on [okHttpClient], configured once with these timeouts: one cancellable exchange
 * with a bounded body, no retries. [followRedirects] false returns a 3xx as-is, so the caller can vet
 * `Location` before following it.
 */
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
        // A redirect never crosses between HTTPS and HTTP, so query-string API keys cannot leave TLS.
        .followSslRedirects(false)
        .build()
    return { spec -> client.newCall(spec.toRequest()).await { response -> response.toSourceResponse(spec.maxBodyBytes) } }
}

/** Malformed input surfaces as the transport failure adapters already handle. */
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

/** HTTP/2 sends lower-case names and HTTP/1.1 whatever the server wrote, so lookups must not depend on either. */
private fun Headers.toCaseInsensitiveMap(): Map<String, List<String>> =
    TreeMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER).also { map ->
        forEach { (name, value) -> map.getOrPut(name) { mutableListOf() } += value }
    }

/** Keeps the agent these requests always sent where a source sets none; some providers reject library agents. */
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
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
        // Query-component spaces should be encoded as %20 for broader API compatibility.
        .replace("+", "%20")
}
