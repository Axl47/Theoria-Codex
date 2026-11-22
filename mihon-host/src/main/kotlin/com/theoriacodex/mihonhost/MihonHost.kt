package com.theoriacodex.mihonhost

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt

/**
 * The run-time environment Mihon extensions expect from their host app: the service locator they ask for an
 * `Application`, a `Json` and a `NetworkHelper`, and the host's version. Installed once, before any extension loads.
 *
 * Extensions get an HTTP client of their own, with Mihon's timeouts and interceptor names and none of the app's
 * interceptors or cookies, so they behave here as they do in Mihon.
 */
object MihonHost {
    /** HTTP requests extensions have started, so a caller can report what one step cost. */
    val requestCount = AtomicLong()

    fun install(application: Application, versionName: String, versionCode: Int) {
        AppInfo.hostVersionName = versionName
        AppInfo.hostVersionCode = versionCode
        Injekt.bind(Application::class.java, application)
        Injekt.bind(Context::class.java, application)
        Injekt.bind(Json::class.java, Json { ignoreUnknownKeys = true; explicitNulls = false })
        Injekt.bind(NetworkHelper::class.java, NetworkHelper(extensionClient()) { DEFAULT_USER_AGENT })
    }

    // Extensions rebuild this client and require the three named interceptors, in Mihon's order.
    internal fun extensionClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor { DEFAULT_USER_AGENT })
        .addInterceptor(CloudflareInterceptor())
        .addNetworkInterceptor { chain ->
            requestCount.incrementAndGet()
            chain.proceed(chain.request())
        }
        .build()

    /** Mihon's default, the agent extensions are written and tested against. */
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_MINUTES = 2L
}
