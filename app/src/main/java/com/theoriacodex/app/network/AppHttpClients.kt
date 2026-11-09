package com.theoriacodex.app.network

import com.theoriacodex.app.customsites.BrowserVerifiedSites
import com.theoriacodex.sources.http.unexpectedFailuresAsIoExceptions
import com.theoriacodex.sources.http.unqueuedSourceDispatcher
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Every HTTP client in the app, derived from one so they share a connection pool. OkHttp 5 races IPv6 against IPv4
 * on each new connection, so a network with broken IPv6 costs a quarter of a second instead of a whole connect
 * timeout per address. Each client below differs only in how it queues requests and how long it waits.
 */
internal object AppHttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .fastFallback(true)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .cookieJar(BrowserVerifiedSites.cookieJar)
            .addInterceptor(unexpectedFailuresAsIoExceptions)
            .build()
    }

    /** Provider APIs and indexes. */
    val sources: OkHttpClient by lazy {
        shared.newBuilder().dispatcher(unqueuedSourceDispatcher()).build()
    }

    /** Coil. OkHttp's default of five per host lets a few large GIFs stall every thumbnail behind them. */
    val images: OkHttpClient by lazy {
        shared.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST })
            .build()
    }

    /** Whole-file downloads: GIFs, Pixiv animations and offline saves. */
    val downloads: OkHttpClient by lazy {
        shared.newBuilder()
            .dispatcher(unqueuedSourceDispatcher())
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * OkHttp's default of five idle connections across all hosts discards most of a burst, so the next scroll pays for
 * new handshakes. Simultaneous HTTP/1.1 feed previews need one connection each, and a single search touches many hosts.
 */
private const val MAX_IDLE_CONNECTIONS = 32
private const val IDLE_KEEP_ALIVE_MINUTES = 5L
private const val IMAGE_MAX_REQUESTS_PER_HOST = 16
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 12_000L
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000L
