package com.theoriacodex.app.series

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.Context
import android.net.ConnectivityManager
import com.theoriacodex.app.AppJob
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.app.media.MediaHttpException
import com.theoriacodex.app.media.UnsupportedMediaException
import com.theoriacodex.app.media.decodableImageType
import com.theoriacodex.app.media.downloadMediaFile
import com.theoriacodex.data.repository.CacheSettings
import com.theoriacodex.data.repository.ChapterUnavailable
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.DrainProgress
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal suspend fun downloadChapterPage(url: String, file: File, client: OkHttpClient): String {
    try {
        downloadMediaFile(url, emptyMap(), file, client = client)
    } catch (error: MediaHttpException) {
        if (error.code == HTTP_NOT_FOUND || error.code == HTTP_GONE) throw ChapterUnavailable("A page is gone")
        throw error
    } catch (_: UnsupportedMediaException) {
        throw ChapterUnavailable("A page is not an image")
    }
    return decodableImageType(file) ?: throw ChapterUnavailable("A page is not an image")
}

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410

internal class SeriesDownloadScheduler(
    private val context: Context,
    private val downloadSettings: suspend () -> CacheSettings,
) : DownloadScheduler {
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    override suspend fun schedule(automatic: Boolean) {
        if (automatic) {
            val automaticJob = AppJob.AUTOMATIC_DOWNLOAD.builder(context).setPersisted(true)
            scheduler.schedule(automaticJob.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build())
            return
        }
        val settings = downloadSettings()
        val network = when {
            !settings.downloadsOverMetered -> JobInfo.NETWORK_TYPE_UNMETERED
            !settings.downloadsOverRoaming -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
        val userInitiated = AppJob.USER_DOWNLOAD.builder(context).setPersisted(true).setRequiredNetworkType(network)
        val scheduled = try {
            scheduler.schedule(userInitiated.setUserInitiated(true).build())
        } catch (_: IllegalArgumentException) {
            JobScheduler.RESULT_FAILURE
        }
        if (scheduled != JobScheduler.RESULT_SUCCESS) scheduler.schedule(userInitiated.setUserInitiated(false).build())
    }

    override suspend fun ensureScheduled(automatic: Boolean) {
        if (scheduler.getPendingJob(if (automatic) AppJob.AUTOMATIC_DOWNLOAD.id else AppJob.USER_DOWNLOAD.id) == null) schedule(automatic)
    }
}

class SeriesDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        showProgress(params, null)
        runs[params.jobId] = scope.launch {
            val retry = runCatchingExceptionsPreservingCancellation { drain(params) }.getOrElse { error ->
                showDownloadsWaiting(this@SeriesDownloadJobService, params.automatic, 0, null)
                val paused = error is IOException
                Diagnostics.report("series", if (paused) "Chapter downloads paused" else "Chapter downloads stopped", error)
                paused
            }
            if (!params.isUserInitiatedJob) cancelDownloadProgress(this@SeriesDownloadJobService)
            runs.remove(params.jobId, coroutineContext.job)
            jobFinished(params, retry)
        }
        return true
    }

    private suspend fun drain(params: JobParameters): Boolean {
        val automatic = params.automatic
        if (automatic && getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) return true
        val series = (application as TheoriaAppContainerOwner).awaitAppContainer().series ?: return false
        val result = series.downloads.drain(automatic) { showProgress(params, it) }
        if (result.failed.isNotEmpty()) notifyDownloadsFailed(this, result.failed)
        val shared = result.waiting.map { it.series }.distinct().singleOrNull()?.let { series.repository.series(it)?.series }
        showDownloadsWaiting(this, automatic, result.waiting.size, shared)
        return result.retry
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        if (!params.isUserInitiatedJob) cancelDownloadProgress(this)
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showProgress(params: JobParameters, progress: DrainProgress?) {
        val notification = downloadProgressNotification(this, progress)
        if (params.isUserInitiatedJob) {
            setNotification(params, DOWNLOAD_PROGRESS_NOTIFICATION_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        } else {
            postDownloadProgress(this, notification)
        }
    }
}

private val JobParameters.automatic get() = jobId == AppJob.AUTOMATIC_DOWNLOAD.id
