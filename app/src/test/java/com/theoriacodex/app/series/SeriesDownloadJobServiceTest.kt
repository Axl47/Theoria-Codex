package com.theoriacodex.app.series

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.AppJob
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.FeatureDependencies
import com.theoriacodex.app.di.SeriesDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.di.UpdateDependencies
import com.theoriacodex.app.di.WorkflowDependencies
import com.theoriacodex.app.customsites.CustomSitesController
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.storage.ApplicationDataState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(application = DrainApplication::class, shadows = [FinishRecordingJobService::class])
class SeriesDownloadJobServiceTest {
    private val application = ApplicationProvider.getApplicationContext<DrainApplication>()
    private val repository = StoredOnly(StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/series/a"), "Series")), emptyList())

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the automatic lane waits on a metered network without opening the queue`() {
        activeNetwork(ConnectivityManager.TYPE_MOBILE)

        assertEquals(Finished(reschedule = true), run(AppJob.AUTOMATIC_DOWNLOAD))
        assertEquals(0, application.opened)
    }

    @Test
    fun `a queue that cannot be read retries later`() {
        activeNetwork(ConnectivityManager.TYPE_WIFI)
        application.container = { seriesContainer(unreadableQueue()) }

        assertEquals(Finished(reschedule = true), run(AppJob.USER_DOWNLOAD))
    }

    @Test
    fun `a failed drain takes down its lane's waiting notice and leaves the other lane's`() {
        activeNetwork(ConnectivityManager.TYPE_WIFI)
        application.container = { seriesContainer(unreadableQueue()) }
        showDownloadsWaiting(application, automatic = false, waiting = 2, series = null)
        showDownloadsWaiting(application, automatic = true, waiting = 1, series = null)

        run(AppJob.USER_DOWNLOAD)

        val posted = application.getSystemService(NotificationManager::class.java).activeNotifications.map { it.tag }
        assertEquals(listOf("automatic"), posted)
    }

    @Test
    fun `any other failure stops the lane without retrying`() {
        activeNetwork(ConnectivityManager.TYPE_WIFI)
        application.container = { error("Broken container") }

        assertEquals(Finished(reschedule = false), run(AppJob.AUTOMATIC_DOWNLOAD))
    }

    private data class Finished(val reschedule: Boolean)

    private fun run(job: AppJob): Finished {
        val service = Robolectric.buildService(SeriesDownloadJobService::class.java).create().get()
        assertTrue(service.onStartJob(parameters(job.id)))
        return Finished(Shadow.extract<FinishRecordingJobService>(service).finished.get(10, TimeUnit.SECONDS))
    }

    private fun parameters(jobId: Int): JobParameters =
        JobParameters::class.java.constructors.single { it.parameterCount == JOB_PARAMETER_COUNT }
            .newInstance(null, null, jobId, null, null, null, 0, false, false, false, null, null, null) as JobParameters

    private fun activeNetwork(type: Int) {
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)
        shadowOf(application.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(info)
    }

    private fun unreadableQueue(): SeriesDownloads {
        return SeriesDownloads(
            store = OfflineMediaStore(folder.newFolder("downloads")),
            queueFile = folder.newFolder("queue.json"),
            repository = repository,
            source = { null },
            covers = SeriesCovers { null },
            fetch = { _, _ -> error("Nothing downloads here") },
            scheduler = object : DownloadScheduler {
                override suspend fun schedule(automatic: Boolean) = Unit

                override suspend fun ensureScheduled(automatic: Boolean) = Unit
            },
            deleteAfterReading = { false },
        )
    }

    private fun seriesContainer(downloads: SeriesDownloads): TheoriaAppContainer {
        val scope = CoroutineScope(SupervisorJob())
        val series = SeriesDependencies(repository, { emptyList() }, { null }, NoExtensions, NoRepos, downloads, scope, SeriesCovers { null }, log = {})
        return object : TheoriaAppContainer {
            override val data: DataDependencies get() = error("unused")
            override val sources: SourceDependencies get() = error("unused")
            override val updates: UpdateDependencies get() = error("unused")
            override val features: FeatureDependencies get() = error("unused")
            override val workflows: WorkflowDependencies get() = error("unused")
            override val customSites: CustomSitesController get() = error("unused")
            override val series = series
            override val applicationScope = scope
        }
    }

    private companion object {
        const val JOB_PARAMETER_COUNT = 13
    }
}

@Implements(JobService::class)
class FinishRecordingJobService : ShadowJobService() {
    val finished = CompletableFuture<Boolean>()

    @Implementation
    override fun jobFinished(params: JobParameters, wantsReschedule: Boolean) {
        finished.complete(wantsReschedule)
    }
}

class DrainApplication : Application(), TheoriaAppContainerOwner {
    var container: suspend () -> TheoriaAppContainer = { error("No container") }
    var opened = 0

    override val appContainerState: StateFlow<ApplicationDataState<TheoriaAppContainer>> get() = error("unused")

    override fun startAppContainer() = Unit

    override suspend fun awaitAppContainer(): TheoriaAppContainer {
        opened++
        return container()
    }

    override suspend fun retryAppContainer() = awaitAppContainer()
}
