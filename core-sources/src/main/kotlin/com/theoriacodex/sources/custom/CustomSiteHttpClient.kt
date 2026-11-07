package com.theoriacodex.sources.custom

import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.sources.http.DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES
import com.theoriacodex.sources.http.HttpRequestSpec
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.buildUrl
import com.theoriacodex.sources.http.executeHttpRequest
import com.theoriacodex.sources.http.toTextResponse
import com.theoriacodex.sources.http.withByteRange
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Transport for user-added sites, used by the prober and by custom-site adapters (plan C7). Requests
 * go out with redirects off; a redirect is followed (at most [maxRedirects] hops) only when it stays
 * on HTTPS and on the same site, so query-string API keys never reach another host or travel in
 * cleartext. A refused redirect is never requested. No retries; GET only.
 */
class CustomSiteHttpClient internal constructor(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val maxRedirects: Int,
    private val transport: suspend (HttpRequestSpec) -> SourceByteResponse,
) : SourceHttpClient {
    /** Probes use the 10 s defaults; adapters may pass a longer [readTimeoutMs]. */
    constructor(
        connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ) : this(connectTimeoutMs, readTimeoutMs, MAX_REDIRECTS, ::executeHttpRequest)

    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        getBytes(url, query, headers, range = null, maxBodyBytes = DEFAULT_MAX_SOURCE_TEXT_BODY_BYTES).toTextResponse()

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        var current = parseUri(buildUrl(url, query))
        require(current.scheme.equals("https", ignoreCase = true)) { "Custom sites are requested over HTTPS only" }
        val requestHeaders = headers.withByteRange(range)
        repeat(maxRedirects + 1) {
            val response = transport(
                HttpRequestSpec(
                    method = "GET",
                    url = current.toASCIIString(),
                    headers = requestHeaders,
                    body = null,
                    maxBodyBytes = maxBodyBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    followRedirects = false,
                ),
            )
            val location = response.redirectLocation() ?: return response
            current = vetRedirect(from = current, to = current.resolve(parseUri(location)))
        }
        throw IOException("Too many redirects")
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        throw UnsupportedOperationException("Custom sites are read-only")

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val MAX_REDIRECTS = 3
    }
}

/** A redirect the custom-site policy refused; [failure] is how a probe reports it. */
class CustomSiteRedirectException(val failure: ProbeFailure, message: String) : IOException(message)

/**
 * Allows HTTPS → HTTPS on the same host and port. A host and its `www.` twin count as one site,
 * as they do for source ids (plan D2): `sakugabooru.com` answers with a redirect to `www.`.
 */
internal fun vetRedirect(from: URI, to: URI): URI {
    if (!to.scheme.equals("https", ignoreCase = true)) {
        throw CustomSiteRedirectException(ProbeFailure.INSECURE_REDIRECT, "Refused a redirect away from HTTPS")
    }
    if (siteHost(from) != siteHost(to) || effectivePort(from) != effectivePort(to)) {
        throw CustomSiteRedirectException(ProbeFailure.CROSS_HOST_REDIRECT, "Refused a redirect to another host")
    }
    return to
}

private fun SourceByteResponse.redirectLocation(): String? =
    takeIf { statusCode in REDIRECT_STATUSES }
        ?.headers?.entries?.firstOrNull { (name, _) -> name.equals("Location", ignoreCase = true) }
        ?.value?.firstOrNull()?.takeIf(String::isNotBlank)

private fun siteHost(uri: URI): String? = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")

private fun effectivePort(uri: URI): Int = if (uri.port == -1) HTTPS_PORT else uri.port

private fun parseUri(value: String): URI = try {
    URI(value)
} catch (error: URISyntaxException) {
    throw IOException("Malformed URL", error)
}

private const val HTTPS_PORT = 443
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
