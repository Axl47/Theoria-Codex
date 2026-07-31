package com.theoriacodex.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.theoriacodex.app.di.DefaultTheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.LegacyAnimatedWebPDecoder
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

    internal val videoPlaybackInfrastructure: VideoPlaybackInfrastructure by lazy {
        VideoPlaybackInfrastructure(this)
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
                ?: DefaultTheoriaAppContainer(this@TheoriaApplication).also { created ->
                    initializingContainer = created
                }
            container.awaitDurableStores()
            container
        }
        startAppContainerIfAllowed(
            benchmarkFixturesEnabled = BuildConfig.BENCHMARK_FIXTURES_ENABLED,
            packageName = packageName,
            processName = currentProcessName(),
            start = ::startAppContainer,
        )
    }

    override fun startAppContainer() = readiness.start()

    override suspend fun awaitAppContainer(): TheoriaAppContainer = readiness.awaitReady()

    override suspend fun retryAppContainer(): TheoriaAppContainer = readiness.retry()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(LegacyAnimatedWebPDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
        val processId = android.os.Process.myPid()
        return activityManager?.runningAppProcesses
            ?.firstOrNull { process -> process.pid == processId }
            ?.processName
    }
}

internal fun shouldStartAppContainer(
    benchmarkFixturesEnabled: Boolean,
    packageName: String,
    processName: String?,
): Boolean {
    val benchmarkFixtureProcess = "$packageName:benchmarkFixture"
    return !benchmarkFixturesEnabled || processName != benchmarkFixtureProcess
}

internal inline fun startAppContainerIfAllowed(
    benchmarkFixturesEnabled: Boolean,
    packageName: String,
    processName: String?,
    start: () -> Unit,
) {
    if (shouldStartAppContainer(benchmarkFixturesEnabled, packageName, processName)) {
        start()
    }
}
