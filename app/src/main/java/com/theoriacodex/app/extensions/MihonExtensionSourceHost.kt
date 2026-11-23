package com.theoriacodex.app.extensions

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.FileObserver
import android.util.Log
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.network.AndroidBrowserCookieStore
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.mihonhost.ExtensionImages
import com.theoriacodex.mihonhost.ExtensionLoader
import com.theoriacodex.mihonhost.ExtensionPackages
import com.theoriacodex.mihonhost.ExtensionRegistry
import com.theoriacodex.mihonhost.ExtensionSite
import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.SourceSettings
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor

/** What the app's browser check needs from extension sources. */
interface ExtensionSites {
    /** The sources that are on, with their names and the hosts they call; loads nothing. */
    fun sites(): Map<SourceKey, ExtensionSite>

    /** Changes when [sites] learns another host a source calls. */
    val siteChanges: Flow<*>

    /** Whether the extensions' own client now gets [url] without a challenge, sending [userAgent]. */
    suspend fun clearsChallenge(url: String, userAgent: String): Boolean
}

/**
 * Turns Mihon extensions into series sources: third-party code, loaded into the app. It finds the extensions Mihon
 * installed as packages and, in debug builds only, APK files pushed for tests with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/`.
 *
 * Nothing loads at startup, and nothing ever loads but the extensions the user turned on in the Extensions page;
 * [ExtensionRegistry] holds that rule. Those load once, the first time [sources] is called or [mediaInterceptor] is
 * asked for an image only an extension can fetch. [covers] tags a stored cover without loading anything.
 */
internal class MihonExtensionSourceHost : ExtensionSites {
    private lateinit var registry: ExtensionRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watching: Lazy<Unit>
    private lateinit var packages: ExtensionPackages

    /** Held so the debug folder stays watched; see [watchPackages]. */
    @Volatile
    private var folderWatch: FileObserver? = null

    /** The user's choices, which open with the app's data, after the HTTP clients that already hold [mediaInterceptor]. */
    private val choices = CompletableDeferred<ExtensionChoiceStore>()

    // Loads the extensions on an HTTP client's thread for the first extension image; after that, images only read its table.
    private lateinit var images: ExtensionImages

    /** Goes on the app's media clients: answers requests only an extension can fetch and passes the rest on. */
    val mediaInterceptor: Interceptor get() = images

    /** Turns any stored series cover into the address to load, an extension's tagged for it; loads nothing. */
    val covers: SeriesCovers get() = images

    /** The extensions on the device and the user's choices about them, for the Extensions page. */
    val extensions: SeriesExtensions get() = registry.also { watching.value }

    /**
     * Names the application extensions load into, the hidden browsers that pass their checks out of sight (shared with
     * the app's own client) and the record of the checks they cannot pass. Called once, before anything else; loads nothing.
     */
    fun attach(application: Application, browsers: ChallengeBrowsers, outages: () -> HostOutageTracker) {
        // Release builds run installed packages only; the folder is how tests hand a debug build an APK.
        val key = lazy { MihonHost.imageKey(application) }
        images = ExtensionImages(key) { runBlocking { sources() } }
        // Screens tag covers as they draw, so the key file is read here rather than on the main thread.
        scope.launch(Dispatchers.IO) { key.value }
        packages = ExtensionPackages(application) { if (BuildConfig.DEBUG) application.getExternalFilesDir(DIRECTORY) else null }
        val loader = ExtensionLoader(application, packages)
        // What extensions ask their host for is set up when the first one loads, not at startup.
        val hostInstalled = lazy {
            MihonHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, AndroidBrowserCookieStore, browsers, outages())
        }
        registry = ExtensionRegistry(
            packages = packages::list,
            load = { extension ->
                hostInstalled.value
                loader.load(extension)
            },
            choices = object : ExtensionChoiceStore {
                override suspend fun choices(): ExtensionChoices = choices.await().choices()

                override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = choices.await().update(transform)
            },
            images = images,
            settings = { source -> SourceSettings(application, source, source.sourcePreferences()) },
            deviceLanguages = { application.resources.configuration.locales.toLanguageTags().split(',') },
            log = { Log.i(TAG, it) },
        )
        watching = lazy { watchPackages(application) }
    }

    /**
     * An extension installed, updated, removed, hidden or unhidden while the app runs is noticed at once: the registry
     * lists the device again, which updates the sources that fetch images and, when the extensions changed, moves its
     * revision and so reloads the Series tab. This is the only way the registry learns of changes, so it starts before
     * the extensions first load. Not at [attach], which runs before the application can register anything.
     * Debug builds also watch the folder of pushed APK files, which sends no broadcast.
     */
    private fun watchPackages(application: Application) {
        val changed = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { registry.recheck() }
            }
        }
        application.registerReceiver(receiver, changed, Context.RECEIVER_NOT_EXPORTED)
        // Finding the folder can create it, which is kept off the main thread.
        scope.launch(Dispatchers.IO) { folderWatch = packages.watchFolder { scope.launch { registry.recheck() } } }
    }

    /** Says where the user's choices are kept. Called once, when the app's data opens; everything else waits for it. */
    fun bind(choices: ExtensionChoiceStore) {
        this.choices.complete(choices)
    }

    override fun sites(): Map<SourceKey, ExtensionSite> = registry.sites()

    override val siteChanges: Flow<*> get() = registry.siteChanges

    override suspend fun clearsChallenge(url: String, userAgent: String): Boolean = MihonHost.clearsChallenge(url, userAgent)

    /**
     * The sources of the extensions and languages that are on. The first call loads them, which can take seconds;
     * later calls answer at once. Never throws: an extension that fails to load is left out.
     */
    suspend fun sources(): List<SeriesSource> {
        watching.value
        return registry.sources()
    }

    private companion object {
        const val TAG = "MihonHost"
        const val DIRECTORY = "mihon-extensions"
    }
}
