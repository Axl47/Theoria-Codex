package com.theoriacodex.extensionhost

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import com.theoriacodex.sources.http.BrowserAddressDns
import com.theoriacodex.sources.http.BrowserCookieJar
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.ChallengePass
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.isCloudflareChallenge
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt

object ExtensionHost {
    val requestCount = AtomicLong()

    @Volatile
    private var client: OkHttpClient? = null

    internal fun install(
        application: Application,
        versionName: String,
        versionCode: Int,
        cookies: BrowserCookieStore,
        browsers: ChallengeBrowsers,
        outages: HostOutageTracker,
    ) {
        AppInfo.hostVersionName = versionName
        AppInfo.hostVersionCode = versionCode
        val extensionApplication = ExtensionApplication(application)
        Injekt.bind(Application::class.java, extensionApplication)
        Injekt.bind(Context::class.java, extensionApplication)
        Injekt.bind(Json::class.java, Json { ignoreUnknownKeys = true; explicitNulls = false })
        val client = extensionClient(cookies, browsers, outages).also { ExtensionHost.client = it }
        Injekt.bind(NetworkHelper::class.java, NetworkHelper(client) { DEFAULT_USER_AGENT })
    }

    suspend fun clearsChallenge(url: String, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        val client = ExtensionHost.client ?: return@withContext false
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { !it.isCloudflareChallenge() }
        } catch (_: HostUnavailableException) {
            false
        }
    }

    internal fun extensionClient(cookies: BrowserCookieStore, browsers: ChallengeBrowsers, outages: HostOutageTracker): OkHttpClient = OkHttpClient.Builder()
        .dns(BrowserAddressDns())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .cookieJar(BrowserCookieJar(cookies) { true })
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor { DEFAULT_USER_AGENT })
        .addInterceptor(CloudflareInterceptor(ChallengePass(browsers, outages::holdsOffChallengePass), outages))
        .addNetworkInterceptor { chain ->
            requestCount.incrementAndGet()
            chain.proceed(chain.request())
        }
        .build()

    private class ExtensionApplication(private val base: Application) : Application() {
        private val opened = HashMap<String, FilePreferences>()
        init {
            attachBaseContext(base)
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = synchronized(opened) {
            opened.getOrPut(name.orEmpty()) { FilePreferences(preferencesFile(base, name.orEmpty())) }
        }
    }

    fun preferencesFile(context: Context, name: String): File =
        File(File(context.noBackupFilesDir, PREFERENCES_DIRECTORY), URLEncoder.encode(name, "UTF-8") + ".json")

    fun deleteLegacyPreferences(context: Context) {
        File(context.dataDir, "shared_prefs").list().orEmpty()
            .filter { it.startsWith(LEGACY_PREFERENCES_PREFIX) && it.endsWith(".xml") }
            .forEach { context.deleteSharedPreferences(it.removeSuffix(".xml")) }
    }

    fun imageKey(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, "extension_image_key")
        runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == ExtensionImages.KEY_BYTES }?.let { return it }
        val key = ByteArray(ExtensionImages.KEY_BYTES).also(SecureRandom()::nextBytes)
        runCatching {
            val pending = File(file.path + ".new")
            pending.writeBytes(key)
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return key
    }

    private const val PREFERENCES_DIRECTORY = "mihon_preferences"
    private const val LEGACY_PREFERENCES_PREFIX = "mihon_"

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_MINUTES = 2L
}
