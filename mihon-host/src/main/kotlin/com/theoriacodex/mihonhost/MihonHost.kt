package com.theoriacodex.mihonhost

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
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
