package com.theoriacodex.sources.testing

import com.sun.net.httpserver.HttpServer
import com.theoriacodex.sources.http.DefaultSourceHttpClient
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider host that answers every request with a Cloudflare challenge, reached through a real
 * [DefaultSourceHttpClient] with a [HostOutageTracker], so adapters meet the outage the tracker records.
 * Requests to [mirrorPrefix] get [mirrorResponse] instead; [hits] counts requests that reached the host.
 */
class ChallengingHost(
    private val mirrorPrefix: String,
    private val mirrorResponse: SourceHttpResponse,
) : AutoCloseable {
    val hits = AtomicInteger()
    val mirrorRequests = mutableListOf<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("cf-mitigated", "challenge")
            val body = "<html>Just a moment... cloudflare</html>".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val real = DefaultSourceHttpClient(maxRetries = 0, outages = HostOutageTracker())

    /** Sends provider requests to the local host, keeping their path and query. */
    val client: SourceHttpClient = object : SourceHttpClient {
        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            if (url.startsWith(mirrorPrefix)) {
                mirrorRequests += url
                return mirrorResponse
            }
            val local = URI(url).let { "http://127.0.0.1:${server.address.port}${it.rawPath}" }
            return real.get(local, query, headers)
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("unused")
    }

    override fun close() = server.stop(0)
}
