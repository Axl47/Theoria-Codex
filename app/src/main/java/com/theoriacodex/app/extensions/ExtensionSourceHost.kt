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
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SourceAvailability
import com.theoriacodex.extensionhost.AniyomiKind
import com.theoriacodex.extensionhost.ExtensionCatalog
import com.theoriacodex.extensionhost.ExtensionHost
import com.theoriacodex.extensionhost.ExtensionImages
import com.theoriacodex.extensionhost.ExtensionLoader
import com.theoriacodex.extensionhost.ExtensionPackages
import com.theoriacodex.extensionhost.ExtensionRegistry
import com.theoriacodex.extensionhost.ExtensionSite
import com.theoriacodex.extensionhost.MihonKind
import com.theoriacodex.extensionhost.SourceSettings
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient

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
 * Turns Mihon (manga) and Aniyomi (anime) extensions into series sources: third-party code, loaded into the app. It
 * finds the extensions this app installed from the user's repos ([repos]), those Mihon or Aniyomi installed as
 * packages and, in debug builds only, APK files pushed for tests with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/`.
 *
 * Nothing loads at startup, and nothing ever loads but the extensions the user turned on in the Extensions page;
 * [ExtensionRegistry] holds that rule. Each loads once, the first time it is needed: [source] or an image only an
 * extension can fetch ([mediaInterceptor]) loads the one extension that source needs, and [sources] loads them all.
 * [covers] tags a stored cover without loading anything.
 */
internal class ExtensionSourceHost : ExtensionSites {
    private lateinit var registry: ExtensionRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watching: Lazy<Unit>
    private lateinit var packages: ExtensionPackages
    private lateinit var imageKey: Lazy<ByteArray>

    /** Held so the debug folder stays watched; see [watchPackages]. */
    @Volatile
    private var folderWatch: FileObserver? = null

    /** The user's choices, which open with the app's data, after the HTTP clients that already hold [mediaInterceptor]. */
    private val choices = CompletableDeferred<ExtensionChoiceStore>()

    /** [choices] as a store that waits for them to open. */
    private val choiceStore = object : ExtensionChoiceStore {
        override suspend fun choices(): ExtensionChoices = choices.await().choices()

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = choices.await().update(transform)
    }

    /** Where extensions installed from repos are kept: never backed up, and written only by [repos]. */
    private lateinit var installedFolder: Lazy<File>

    // Loads a source's extension on an HTTP client's thread for its first image; after that, its images only read the table.
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
        imageKey = lazy { ExtensionHost.imageKey(application) }
        images = ExtensionImages(imageKey) { key -> runBlocking { source(key) } }
        // Not read here: the application has no files yet when it attaches the host.
        installedFolder = lazy { File(application.noBackupFilesDir, INSTALLED_DIRECTORY) }
        packages = ExtensionPackages(application, KINDS, installedFolder::value) { if (BuildConfig.DEBUG) application.getExternalFilesDir(DIRECTORY) else null }
        val loader = ExtensionLoader(application, packages)
        // What extensions ask their host for is set up when the first one loads, not at startup.
        val hostInstalled = lazy {
            ExtensionHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, AndroidBrowserCookieStore, browsers, outages())
        }
        registry = ExtensionRegistry(
            packages = packages::list,
            load = { extension, services ->
                hostInstalled.value
                loader.load(extension, services)
            },
            choices = choiceStore,
            images = images,
            settings = { hook -> SourceSettings(application, hook) },
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
        // A burst of changes, such as a wave of app updates, lists the device once more after the listing under way.
        val rechecks = Channel<Unit>(Channel.CONFLATED)
        scope.launch {
            for (change in rechecks) {
                // One failed listing, such as a binder error, must not stop later changes being noticed.
                runCatchingPreservingCancellation { registry.recheck() }.onFailure { Log.w(TAG, "Recheck failed: ${it.javaClass.simpleName}") }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // An update also sends REMOVED and ADDED marked as replacing; its REPLACED is enough.
                if (intent.action != Intent.ACTION_PACKAGE_REPLACED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                rechecks.trySend(Unit)
            }
        }
        application.registerReceiver(receiver, changed, Context.RECEIVER_NOT_EXPORTED)
        // Finding the folder can create it, which is kept off the main thread.
        scope.launch(Dispatchers.IO) { folderWatch = packages.watchFolder { rechecks.trySend(Unit) } }
    }

    /** Says where the user's choices are kept. Called once, when the app's data opens; everything else waits for it. */
    fun bind(choices: ExtensionChoiceStore) {
        this.choices.complete(choices)
        // Screens tag covers as they draw, so the key file is read now, off the main thread. Not at [attach], which
        // runs before the application has a context to find the file with.
        scope.launch(Dispatchers.IO) { imageKey.value }
    }

    /**
     * The user's extension repos and installing from them, fetching over [client]. An install or removal lists the
     * extensions again at once, since Android announces no change to the app's own folder.
     */
    fun repos(client: OkHttpClient): ExtensionRepos = ExtensionCatalog(
        choices = choiceStore,
        // Repos are no site the user signed in to: their requests carry no cookies.
        client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build(),
        kinds = KINDS,
        packages = packages::list,
        read = packages::read,
        folder = installedFolder.value,
        onChanged = { registry.recheck() },
    )

    override fun sites(): Map<SourceKey, ExtensionSite> = registry.sites()

    override val siteChanges: Flow<*> get() = registry.siteChanges

    override suspend fun clearsChallenge(url: String, userAgent: String): Boolean = ExtensionHost.clearsChallenge(url, userAgent)

    /**
     * The sources of the extensions and languages that are on. The first call loads them all, which can take seconds;
     * later calls answer at once. An extension that fails to load is left out.
     */
    suspend fun sources(): List<SeriesSource> {
        watching.value
        return registry.sources()
    }

    /** Which sources are on and which an installed extension offers but is off; loads every extension that is on, as [sources] does. */
    suspend fun availability(): SourceAvailability {
        watching.value
        return registry.availability()
    }

    /** The source [key] names when it is on, else null; loads only its extension when this device knows which that is. */
    suspend fun source(key: SourceKey): SeriesSource? {
        watching.value
        return registry.source(key)
    }

    private companion object {
        const val TAG = "ExtensionHost"
        // Named when Mihon's were the only extensions; kept so files pushed there for tests keep loading.
        const val DIRECTORY = "mihon-extensions"
        const val INSTALLED_DIRECTORY = "extensions"
        val KINDS = listOf(MihonKind, AniyomiKind)
    }
}
