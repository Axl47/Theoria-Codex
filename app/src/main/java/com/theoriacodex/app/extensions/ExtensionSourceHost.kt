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

interface ExtensionSites {
    fun sites(): Map<SourceKey, ExtensionSite>

    val siteChanges: Flow<*>

    suspend fun clearsChallenge(url: String, userAgent: String): Boolean
}

internal class ExtensionSourceHost : ExtensionSites {
    private lateinit var registry: ExtensionRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var watching: Lazy<Unit>
    private lateinit var packages: ExtensionPackages
    private lateinit var imageKey: Lazy<ByteArray>

    @Volatile
    private var folderWatch: FileObserver? = null

    private val choices = CompletableDeferred<ExtensionChoiceStore>()

    private val choiceStore = object : ExtensionChoiceStore {
        override suspend fun choices(): ExtensionChoices = choices.await().choices()

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = choices.await().update(transform)
    }

    private lateinit var installedFolder: Lazy<File>

    private lateinit var images: ExtensionImages

    val mediaInterceptor: Interceptor get() = images

    val covers: SeriesCovers get() = images

    val extensions: SeriesExtensions get() = registry.also { watching.value }

    fun attach(application: Application, browsers: ChallengeBrowsers, outages: () -> HostOutageTracker) {
        imageKey = lazy { ExtensionHost.imageKey(application) }
        images = ExtensionImages(imageKey) { key -> runBlocking { source(key) } }
        installedFolder = lazy { File(application.noBackupFilesDir, INSTALLED_DIRECTORY) }
        packages = ExtensionPackages(application, KINDS, installedFolder::value) { if (BuildConfig.DEBUG) application.getExternalFilesDir(DIRECTORY) else null }
        val loader = ExtensionLoader(application, packages)
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

    private fun watchPackages(application: Application) {
        val changed = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val rechecks = Channel<Unit>(Channel.CONFLATED)
        scope.launch {
            for (change in rechecks) {
                runCatchingPreservingCancellation { registry.recheck() }.onFailure { Log.w(TAG, "Recheck failed: ${it.javaClass.simpleName}") }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_PACKAGE_REPLACED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                rechecks.trySend(Unit)
            }
        }
        application.registerReceiver(receiver, changed, Context.RECEIVER_NOT_EXPORTED)
        scope.launch(Dispatchers.IO) { folderWatch = packages.watchFolder { rechecks.trySend(Unit) } }
    }

    fun bind(choices: ExtensionChoiceStore) {
        this.choices.complete(choices)
        scope.launch(Dispatchers.IO) { imageKey.value }
    }

    fun repos(client: OkHttpClient): ExtensionRepos = ExtensionCatalog(
        choices = choiceStore,
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

    suspend fun sources(): List<SeriesSource> {
        watching.value
        return registry.sources()
    }

    fun loadedSources(): List<SeriesSource> = registry.loadedSources()

    suspend fun availability(): SourceAvailability {
        watching.value
        return registry.availability()
    }

    suspend fun source(key: SourceKey): SeriesSource? {
        watching.value
        return registry.source(key)
    }

    private companion object {
        const val TAG = "ExtensionHost"
        const val DIRECTORY = "mihon-extensions"
        const val INSTALLED_DIRECTORY = "extensions"
        val KINDS = listOf(MihonKind, AniyomiKind)
    }
}
