package com.theoriacodex.mihonhost

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
        val extensionApplication = ExtensionApplication(application)
        Injekt.bind(Application::class.java, extensionApplication)
        Injekt.bind(Context::class.java, extensionApplication)
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

    /**
     * The `Application` extensions are handed: the real one in everything but preference files, which get a name of
     * their own. A source asks for `source_<id>`, as it does in Mihon, and so can never name one of the app's files.
     * This keeps names apart; it is no barrier against code that runs in the app's process.
     */
    private class ExtensionApplication(base: Application) : Application() {
        init {
            attachBaseContext(base)
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = super.getSharedPreferences(PREFERENCES_PREFIX + name, mode)
    }

    /** Before the name an extension gives a preferences file. */
    const val PREFERENCES_PREFIX = "mihon_"

    /** Mihon's default, the agent extensions are written and tested against. */
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_MINUTES = 2L
}
