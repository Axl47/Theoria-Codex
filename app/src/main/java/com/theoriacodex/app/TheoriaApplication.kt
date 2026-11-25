package com.theoriacodex.app

import android.net.ConnectivityManager
import com.theoriacodex.app.network.ConnectionMemory
import com.theoriacodex.app.network.SharedPreferencesRecentHostStore
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import com.theoriacodex.app.network.AndroidBrowserCookieStore
import com.theoriacodex.app.customsites.SiteCookieBridge
import com.theoriacodex.app.customsites.SiteCookieBridgeOwner
import com.theoriacodex.app.di.DefaultTheoriaAppContainer
import com.theoriacodex.app.extensions.ExtensionSourceHost
import com.theoriacodex.app.series.addExtensionIcons
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.diagnostics.DiagnosticLogStore
import com.theoriacodex.app.diagnostics.DiagnosticReporter
import com.theoriacodex.app.diagnostics.installCrashCapture
import com.theoriacodex.app.diagnostics.installDebugStrictMode
import com.theoriacodex.app.diagnostics.installOffMainChecker
import com.theoriacodex.app.diagnostics.NetworkUsage
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.app.media.AnimatedWebPDecoder
import com.theoriacodex.app.media.UndecodableImageEviction
import com.theoriacodex.app.network.AppHttpClients
import com.theoriacodex.app.network.AppHttpClientsOwner
import com.theoriacodex.app.network.WebViewChallengeBrowser
import com.theoriacodex.app.media.NetworkMonitor
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.ChallengePass
import com.theoriacodex.sources.http.siteRoot
import okhttp3.Request
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.app.viewer.VideoPlaybackInfrastructure
import com.theoriacodex.app.viewer.VideoPlaybackInfrastructureOwner
import com.theoriacodex.data.storage.ApplicationDataReadiness
import com.theoriacodex.data.storage.ApplicationDataState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

class TheoriaApplication :
    Application(),
    ImageLoaderFactory,
    TheoriaAppContainerOwner,
    AppHttpClientsOwner,
    VideoPlaybackInfrastructureOwner,
    SiteCookieBridgeOwner {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var readiness: ApplicationDataReadiness<TheoriaAppContainer>
    private var initializingContainer: DefaultTheoriaAppContainer? = null

    override val siteCookieBridge = SiteCookieBridge(AndroidBrowserCookieStore)

    private val networkOnline by lazy { NetworkMonitor(this).online }

    private val hostOutages by lazy { HostOutageTracker(isOnline = { networkOnline.value }) }

    val challengeBrowsers = ChallengeBrowsers(AndroidBrowserCookieStore, WebViewChallengeBrowser(this))

    private val extensionHost = ExtensionSourceHost().also { it.attach(this, challengeBrowsers) { hostOutages } }

    override val httpClients = AppHttpClients(
        siteCookieBridge.cookieJar,
        ConnectionMemory(
            SharedPreferencesRecentHostStore(this),
            activeNetwork = { getSystemService(ConnectivityManager::class.java).activeNetwork },
        ),
        siteCookieBridge::userAgentFor,
        siteCookieBridge::isAllowed,
        siteCookieBridge::mediaAuthorizationFor,
        extensionHost.mediaInterceptor,
        ChallengePass(challengeBrowsers, { hostOutages.isChallenged(it) }, siteCookieBridge::keepsCookies, Request::siteRoot),
    )

    override val videoPlaybackInfrastructure: VideoPlaybackInfrastructure by lazy {
        VideoPlaybackInfrastructure(this, httpClients.video)
    }

    override val appContainerState: StateFlow<ApplicationDataState<TheoriaAppContainer>>
        get() = readiness.state

    internal var diagnosticReporter: DiagnosticReporter? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) startDiagnostics()
        readiness = ApplicationDataReadiness(
            applicationScope = applicationScope,
            initializationDispatcher = Dispatchers.IO,
        ) {
            val container = initializingContainer
                ?: DefaultTheoriaAppContainer(this@TheoriaApplication, siteCookieBridge, httpClients, hostOutages, networkOnline, extensionHost).also { created ->
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

    private fun startDiagnostics() {
        val store = DiagnosticLogStore(filesDir.resolve("theoria_codex/diagnostics"))
        Diagnostics.install(store)
        installOffMainChecker()
        installDebugStrictMode()
        installCrashCapture(store)
        diagnosticReporter = DiagnosticReporter(
            this,
            store,
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
        )
        Diagnostics.report("app", "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    override fun startAppContainer() = readiness.start()

    override suspend fun awaitAppContainer(): TheoriaAppContainer = readiness.awaitReady()

    override suspend fun retryAppContainer(): TheoriaAppContainer = readiness.retry()

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
        state.value.pictures?.let { pictures -> state.value.applicationScope.launch { pictures.tagger.release() } }
    }

    override fun newImageLoader(): ImageLoader {
        val diskCache = DiskCache.Builder()
            .directory(cacheDir.resolve(IMAGE_DISK_CACHE_DIRECTORY))
            .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
            .build()
        return ImageLoader.Builder(this)
            .components {
                add(AnimatedWebPDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
                addExtensionIcons()
            }
            .diskCache(diskCache)
            .respectCacheHeaders(false)
            .eventListener(NetworkUsage.countingImageResults(UndecodableImageEviction(diskCache)))
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
