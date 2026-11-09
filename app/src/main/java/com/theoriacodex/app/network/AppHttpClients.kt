package com.theoriacodex.app.network

import android.content.Context
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.http.await
import com.theoriacodex.sources.http.unexpectedFailuresAsIoExceptions
import com.theoriacodex.sources.http.unqueuedSourceDispatcher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Every HTTP client in the app, derived from one so they share a connection pool. OkHttp 5 races IPv6 against IPv4
 * on each new connection, so a network with broken IPv6 costs a quarter of a second instead of a whole connect
 * timeout per address, and [memory] removes even that once it has seen the pattern. Each client below differs only
 * in how it queues requests and how long it waits.
 */
internal class AppHttpClients(
    cookieJar: CookieJar,
    private val memory: ConnectionMemory = ConnectionMemory(NoRecentHosts, activeNetwork = { null }),
    private val verifiedUserAgent: (url: String) -> String? = { null },
) {
    private val warmed = AtomicBoolean(false)

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .fastFallback(true)
            .dns(memory)
            .eventListener(memory)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .cookieJar(cookieJar)
            .addInterceptor(unexpectedFailuresAsIoExceptions)
            // A site's clearance cookie only counts with the browser agent that earned it, on media as well as searches.
            .addInterceptor { chain ->
                val request = chain.request()
                val agent = verifiedUserAgent(request.url.toString())
                chain.proceed(if (agent == null) request else request.newBuilder().header("User-Agent", agent).build())
            }
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
            .addInterceptor(StalledPageRescue(rescue = { stalledPageRescue }))
            .build()
    }

    /** A pool of its own, because a stalled page's connection is the one thing a rescue must not reuse. */
    private val stalledPageRescue: OkHttpClient by lazy {
        shared.newBuilder().connectionPool(ConnectionPool(1, IDLE_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)).build()
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

    /**
     * Connects to the hosts used most recently, once per launch, so the first search finds its connections open
     * instead of waiting on DNS, TCP and TLS for each. Only the pooled connection matters, so any answer will do.
     */
    suspend fun warmRecentHosts() {
        if (!warmed.compareAndSet(false, true)) return
        val client = shared.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(WARM_UP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        coroutineScope {
            memory.recentHosts().forEach { host ->
                launch {
                    runCatchingPreservingCancellation {
                        val request = Request.Builder().url("https://$host/").head().header("User-Agent", WARM_UP_USER_AGENT).build()
                        client.newCall(request).await { }
                    }
                }
            }
        }
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
private const val WARM_UP_TIMEOUT_MS = 8_000L
private const val WARM_UP_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"

/** Only a plain test application reaches this; the real one owns clients that carry its cookies. */
private val cookielessHttpClients by lazy { AppHttpClients(CookieJar.NO_COOKIES) }

/** The application's clients, for code that reaches them through a [Context]. */
internal val Context.appHttpClients: AppHttpClients
    get() = (applicationContext as? TheoriaApplication)?.httpClients ?: cookielessHttpClients
