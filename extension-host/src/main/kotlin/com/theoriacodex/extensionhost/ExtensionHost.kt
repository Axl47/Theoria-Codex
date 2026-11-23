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

/**
 * The run-time environment Mihon and Aniyomi extensions expect from their host app: the service locator they ask for an
 * `Application`, a `Json` and a `NetworkHelper`, and the host's version. Installed once, before any extension loads.
 *
 * Extensions get an HTTP client of their own, with Mihon's timeouts and interceptor names (Aniyomi's are the same) and
 * none of the app's interceptors, so they behave here as they do there. Like Mihon's, it shares the system browser's
 * cookies.
 */
object ExtensionHost {
    /** HTTP requests extensions have started, so a caller can report what one step cost. */
    val requestCount = AtomicLong()

    /** The extensions' client, once [install] has made it. */
    @Volatile
    private var client: OkHttpClient? = null

    /**
     * [cookies] is the system browser's cookie store, which the extensions' client and its browser checks share;
     * [browsers] pass checks out of sight, shared with the app's own client, and [outages] records the ones they cannot,
     * as for every other source.
     */
    fun install(
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

    /**
     * Whether the extensions' own client now gets [url] without a challenge, sending [userAgent]: the app's browser
     * check is passed for an extension source once this is true. False before any extension has loaded. Throws
     * [java.io.IOException] when the site cannot be reached at all.
     */
    suspend fun clearsChallenge(url: String, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        val client = ExtensionHost.client ?: return@withContext false
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { !it.isCloudflareChallenge() }
        } catch (_: HostUnavailableException) {
            false
        }
    }

    /**
     * Extensions rebuild this client and require the three named interceptors, in Mihon's order. Its cookie jar holds
     * every host's cookies, as Mihon's does: extensions are written against it, and many keep a session in cookies. It
     * is not limited to the hosts the app's own clients may send browser cookies to (`SiteCookieBridge`), because
     * extension code runs in the app's process and could read the store directly, so such a limit would be no barrier.
     * It resolves hosts as the WebView would connect to them ([BrowserAddressDns]), since a clearance cookie a browser
     * check earns is only accepted from the address it was earned from.
     */
    internal fun extensionClient(cookies: BrowserCookieStore, browsers: ChallengeBrowsers, outages: HostOutageTracker): OkHttpClient = OkHttpClient.Builder()
        .dns(BrowserAddressDns())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .cookieJar(BrowserCookieJar(cookies) { true })
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor { DEFAULT_USER_AGENT })
        .addInterceptor(CloudflareInterceptor(ChallengePass(browsers, outages::isChallenged), outages))
        .addNetworkInterceptor { chain ->
            requestCount.incrementAndGet()
            chain.proceed(chain.request())
        }
        .build()

    /**
     * The `Application` extensions are handed: the real one in everything but preferences, which are files of the
     * host's own (see [FilePreferences]). A source asks for `source_<id>`, as it does in Mihon, and so can never name
     * one of the app's files. This keeps names apart; it is no barrier against code that runs in the app's process.
     */
    private class ExtensionApplication(private val base: Application) : Application() {
        private val opened = HashMap<String, FilePreferences>()
        init {
            attachBaseContext(base)
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = synchronized(opened) {
            opened.getOrPut(name.orEmpty()) { FilePreferences(preferencesFile(base, name.orEmpty())) }
        }
    }

    /** Where the preferences an extension calls [name] are kept: under the directory Android never backs up or transfers. */
    fun preferencesFile(context: Context, name: String): File =
        File(File(context.noBackupFilesDir, PREFERENCES_DIRECTORY), URLEncoder.encode(name, "UTF-8") + ".json")

    /**
     * Deletes the `shared_prefs/mihon_<name>.xml` files debug builds wrote before extensions reached a release, which
     * Android backs up. Nothing is imported. Called by the app at startup, whether or not an extension ever loads.
     */
    fun deleteLegacyPreferences(context: Context) {
        File(context.dataDir, "shared_prefs").list().orEmpty()
            .filter { it.startsWith(LEGACY_PREFERENCES_PREFIX) && it.endsWith(".xml") }
            .forEach { context.deleteSharedPreferences(it.removeSuffix(".xml")) }
    }

    /**
     * The key [ExtensionImages] signs its addresses with: made once per install and kept where Android neither backs
     * it up nor transfers it. A key that cannot be read is replaced; addresses signed with the old one then load as
     * ordinary ones until their series is opened again.
     */
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

    /** Mihon's default, the agent extensions are written and tested against. */
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_MINUTES = 2L
}
