package com.theoriacodex.app

import android.net.ConnectivityManager
import com.theoriacodex.app.network.ConnectionMemory
import com.theoriacodex.app.network.SharedPreferencesRecentHostStore
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import com.theoriacodex.app.customsites.AndroidBrowserCookieStore
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.di.DefaultTheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.AnimatedWebPDecoder
import com.theoriacodex.app.media.UndecodableImageEviction
import com.theoriacodex.app.network.AppHttpClients
import com.theoriacodex.app.viewer.VideoPlaybackInfrastructure
import com.theoriacodex.data.storage.ApplicationDataReadiness
import com.theoriacodex.data.storage.ApplicationDataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class TheoriaApplication : Application(), ImageLoaderFactory, TheoriaAppContainerOwner {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var readiness: ApplicationDataReadiness<TheoriaAppContainer>
    private var initializingContainer: DefaultTheoriaAppContainer? = null

    /** The one place browser cookies reach the app's own requests: every client in [httpClients] carries its jar. */
    internal val siteCookieBridge = SiteCookieBridge(AndroidBrowserCookieStore)

    /** Every HTTP client in the app, so they share one connection pool and these cookies. */
    internal val httpClients = AppHttpClients(
        siteCookieBridge.cookieJar,
        ConnectionMemory(
            SharedPreferencesRecentHostStore(this),
            activeNetwork = { getSystemService(ConnectivityManager::class.java).activeNetwork },
        ),
        siteCookieBridge::userAgentFor,
    )

    internal val videoPlaybackInfrastructure: VideoPlaybackInfrastructure by lazy {
        VideoPlaybackInfrastructure(this, httpClients.shared)
    }

    override val appContainerState: StateFlow<ApplicationDataState<TheoriaAppContainer>>
        get() = readiness.state

    override fun onCreate() {
        super.onCreate()
        readiness = ApplicationDataReadiness(
            applicationScope = applicationScope,
            initializationDispatcher = Dispatchers.IO,
        ) {
            val container = initializingContainer
                ?: DefaultTheoriaAppContainer(this@TheoriaApplication, siteCookieBridge, httpClients).also { created ->
                    initializingContainer = created
                }
            container.awaitDurableStores()
            container
        }
        startAppContainerIfAllowed(
            benchmarkFixturesEnabled = BuildConfig.BENCHMARK_FIXTURES_ENABLED,
            developerCopyEnabled = BuildConfig.DEVELOPER_COPY_ENABLED,
            packageName = packageName,
            processName = getProcessName(),
            start = ::startAppContainer,
        )
    }

    override fun startAppContainer() = readiness.start()

    override suspend fun awaitAppContainer(): TheoriaAppContainer = readiness.awaitReady()

    override suspend fun retryAppContainer(): TheoriaAppContainer = readiness.retry()

    /** Coil trims its own memory cache; this covers what the app owns, once the process is at risk of being killed. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) releaseMemoryCaches()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseMemoryCaches()
    }

    private fun releaseMemoryCaches() {
        val state = readiness.state.value as? ApplicationDataState.Ready ?: return
        state.value.sources.pixivUgoiraClient.releaseMemoryCaches()
    }

    override fun newImageLoader(): ImageLoader {
        // Same directory as Coil's default, so the existing cache is kept. Coil's default size
        // (2% of disk, at most 250 MB) let a few Viewer originals evict the grid's thumbnails.
        val diskCache = DiskCache.Builder()
            .directory(cacheDir.resolve(IMAGE_DISK_CACHE_DIRECTORY))
            .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
            .build()
        return ImageLoader.Builder(this)
            .components {
                add(AnimatedWebPDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
            }
            .diskCache(diskCache)
            // Media URLs name files that never change, so a cached copy is used as is. Revalidating it cost a
            // round trip per cached image on origins that send only validators (nginx's default), including
            // every Viewer page a prefetch had already downloaded; no-store responses were never cached.
            .respectCacheHeaders(false)
            .eventListener(UndecodableImageEviction(diskCache))
            .okHttpClient { httpClients.images }
            .build()
    }
}

private const val IMAGE_DISK_CACHE_DIRECTORY = "image_cache"
private const val IMAGE_DISK_CACHE_MAX_BYTES = 512L * 1024L * 1024L

internal fun shouldStartAppContainer(
    benchmarkFixturesEnabled: Boolean,
    packageName: String,
    processName: String?,
    developerCopyEnabled: Boolean = false,
): Boolean {
    if (developerCopyEnabled && (processName == null || processName == "$packageName:developerCopy")) return false
    val benchmarkFixtureProcess = "$packageName:benchmarkFixture"
    return !benchmarkFixturesEnabled || processName != benchmarkFixtureProcess
}

internal inline fun startAppContainerIfAllowed(
    benchmarkFixturesEnabled: Boolean,
    packageName: String,
    processName: String?,
    developerCopyEnabled: Boolean = false,
    start: () -> Unit,
) {
    if (shouldStartAppContainer(benchmarkFixturesEnabled, packageName, processName, developerCopyEnabled)) {
        start()
    }
}
