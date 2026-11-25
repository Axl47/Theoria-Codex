package com.theoriacodex.app.network

import android.content.Context
import com.theoriacodex.app.diagnostics.NetworkUsage
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.http.BrowserAddressDns
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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

class AppHttpClients(
    cookieJar: CookieJar,
    private val memory: ConnectionMemory = ConnectionMemory(NoRecentHosts, activeNetwork = { null }),
    private val verifiedUserAgent: (url: String) -> String? = { null },
    private val sharesBrowserIdentity: (host: String) -> Boolean = { false },
    private val siteAuthorization: (url: String) -> String? = { null },
    private val extensionMedia: Interceptor? = null,
    private val challengePass: Interceptor? = null,
) {
    private val warmed = AtomicBoolean(false)

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .fastFallback(true)
            .dns(BrowserAddressDns(memory, sharesBrowserIdentity))
            .eventListener(memory)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .cookieJar(cookieJar)
            .addInterceptor(unexpectedFailuresAsIoExceptions)
            .build()
    }

    private val media: OkHttpClient by lazy {
        val keyRetry = RefusedMediaKeyRetry(siteAuthorization)
        shared.newBuilder()
            .apply { extensionMedia?.let(::addInterceptor) }
            .addInterceptor { chain ->
                val request = chain.request()
                val agent = verifiedUserAgent(request.url.toString())
                chain.proceed(if (agent == null) request else request.newBuilder().header("User-Agent", agent).build())
            }
            .addInterceptor(keyRetry)
            .addNetworkInterceptor(keyRetry.redirectGuard)
            .build()
    }

    val video: OkHttpClient by lazy { media.newBuilder().countedAs(NetworkUsage.Stack.VIDEO).build() }

    val sources: OkHttpClient by lazy {
        shared.newBuilder().dispatcher(unqueuedSourceDispatcher())
            .apply { challengePass?.let(::addInterceptor) }
            .countedAs(NetworkUsage.Stack.SOURCE)
            .build()
    }

    val images: OkHttpClient by lazy {
        media.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST })
            .addInterceptor(StalledPageRescue(rescue = { stalledPageRescue }))
            .countedAs(NetworkUsage.Stack.IMAGE)
            .build()
    }

    internal val stalledPageRescue: OkHttpClient by lazy {
        shared.newBuilder().connectionPool(ConnectionPool(1, IDLE_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .countedAs(NetworkUsage.Stack.IMAGE)
            .build()
    }

    val downloads: OkHttpClient by lazy {
        media.newBuilder()
            .dispatcher(unqueuedSourceDispatcher())
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .countedAs(NetworkUsage.Stack.DOWNLOAD)
            .build()
    }

    suspend fun warmRecentHosts() {
        if (!warmed.compareAndSet(false, true)) return
        val client = shared.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(WARM_UP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        coroutineScope {
            memory.recentHosts().filterNot(sharesBrowserIdentity).forEach { host ->
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

private fun OkHttpClient.Builder.countedAs(stack: NetworkUsage.Stack) = apply {
    if (NetworkUsage.enabled) addNetworkInterceptor(NetworkUsage.counting(stack))
}

private const val MAX_IDLE_CONNECTIONS = 32
private const val IDLE_KEEP_ALIVE_MINUTES = 5L
private const val IMAGE_MAX_REQUESTS_PER_HOST = 16
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 12_000L
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000L
private const val WARM_UP_TIMEOUT_MS = 8_000L
private const val WARM_UP_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"

private val cookielessHttpClients by lazy { AppHttpClients(CookieJar.NO_COOKIES) }

interface AppHttpClientsOwner {
    val httpClients: AppHttpClients
}

internal val Context.appHttpClients: AppHttpClients
    get() = (applicationContext as? AppHttpClientsOwner)?.httpClients ?: cookielessHttpClients
