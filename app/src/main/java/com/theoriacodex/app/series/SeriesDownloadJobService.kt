package com.theoriacodex.app.series

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.DrainProgress
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Chapter downloads as `JobScheduler` knows them: the user's, and those the check for new chapters queued. */
internal const val USER_DOWNLOAD_JOB_ID = 3102
internal const val AUTOMATIC_DOWNLOAD_JOB_ID = 3103

/**
 * Runs [SeriesDownloadJobService]. Chapters the user queued download in a user-initiated data transfer job on any
 * network: it keeps going once the app leaves the screen and waits for a connection when there is none. Chapters the
 * check for new chapters queued download in an ordinary job that waits for an unmetered network. Both are kept across
 * reboots; the manifest declares the service, `RUN_USER_INITIATED_JOBS` and `RECEIVE_BOOT_COMPLETED` for that.
 */
internal class SeriesDownloadScheduler(private val context: Context) : DownloadScheduler {
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    override fun schedule(automatic: Boolean) {
        if (automatic) {
            scheduler.schedule(job(AUTOMATIC_DOWNLOAD_JOB_ID).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build())
            return
        }
        val userInitiated = job(USER_DOWNLOAD_JOB_ID).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        // Only an app in view may start a user-initiated job. Out of view (resuming after a restart, say) the
        // chapters still download, in an ordinary job.
        val scheduled = try {
            scheduler.schedule(userInitiated.setUserInitiated(true).build())
        } catch (_: IllegalArgumentException) {
            JobScheduler.RESULT_FAILURE
        }
        if (scheduled != JobScheduler.RESULT_SUCCESS) scheduler.schedule(userInitiated.setUserInitiated(false).build())
    }

    override fun ensureScheduled(automatic: Boolean) {
        if (scheduler.getPendingJob(if (automatic) AUTOMATIC_DOWNLOAD_JOB_ID else USER_DOWNLOAD_JOB_ID) == null) schedule(automatic)
    }

    private fun job(id: Int) = JobInfo.Builder(id, ComponentName(context, SeriesDownloadJobService::class.java)).setPersisted(true)
}

/**
 * Drains the chapter download queue while the system lets it, with one ongoing notification for the queue. Each job
 * drains its own lane: the user's job the chapters the user queued, the automatic job those the check queued, so
 * automatic chapters only ever download while the system sees an unmetered network. When both run they take turns
 * chapter by chapter. A chapter in flight when the network changes finishes on the new one.
 */
class SeriesDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        // A user-initiated job must name its notification at once.
        showProgress(params, null)
        runs[params.jobId] = scope.launch {
            // A full disk, say, while the queue is saved: the system runs the job again after a growing wait.
            val retry = try {
                val downloads = (application as TheoriaAppContainerOwner).awaitAppContainer().series?.downloads
                val result = downloads?.drain(automatic = params.jobId == AUTOMATIC_DOWNLOAD_JOB_ID) { showProgress(params, it) }
                if (result != null && result.failed > 0) notifyDownloadsFailed(this@SeriesDownloadJobService, result.failed)
                false
            } catch (error: CancellationException) {
                // onStopJob ended this run; what is left stays queued for the next.
                throw error
            } catch (error: IOException) {
                Diagnostics.report("series", "Chapter downloads paused", error)
                true
            } catch (error: Exception) {
                Diagnostics.report("series", "Chapter downloads stopped", error)
                false
            }
            if (!params.isUserInitiatedJob) cancelDownloadProgress(this@SeriesDownloadJobService)
            runs.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    /** The network went away, say: the system runs the job again later. Stopped by the user, it waits for the next app start. */
    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        if (!params.isUserInitiatedJob) cancelDownloadProgress(this)
        return params.stopReason != JobParameters.STOP_REASON_USER
    }

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
