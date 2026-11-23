package com.theoriacodex.app.vision

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The Library's picture indexing, as `JobScheduler` knows it. */
internal const val LIBRARY_PICTURES_JOB_ID = 3102

/**
 * Hashes and tags the Library's pictures while the device charges ([AppPictures.indexLibrary]), on
 * [scheduleLibraryPictures]' terms. When tagging has more left (the device ran warm, say) it asks to run again; the
 * system stops it when the device is unplugged and runs it again on the next charge.
 */
class LibraryPictureJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running.set(true)
        requested.set(false)
        run = scope.launch {
            val more = try {
                (application as TheoriaAppContainerOwner).awaitAppContainer().pictures?.indexLibrary() ?: false
            } catch (error: CancellationException) {
                // onStopJob ended this run; the job is not reported finished a second time.
                throw error
            } catch (error: Exception) {
                Diagnostics.report("similarity", "The Library's pictures could not be indexed", error)
                false
            }
            // Work that arrived during the run (a post saved while charging) gets a run of its own.
            running.set(false)
            jobFinished(params, more || requested.getAndSet(false))
        }
        return true
    }

    /** Unplugged, or the battery ran low: the next charge picks up where this run stopped. */
    override fun onStopJob(params: JobParameters): Boolean {
        running.set(false)
        run?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Whether a run is under way in this process, and whether work arrived during it. */
private val running = AtomicBoolean(false)
private val requested = AtomicBoolean(false)

/**
 * Asks for [LibraryPictureJobService] to run while the device charges on an unmetered network and its battery is not
 * low, since a run may fetch previews the cache lost. A job already waiting is left alone and a running one is asked to
 * run again, so calling this whenever the Library or the model changes costs nothing.
 */
internal fun scheduleLibraryPictures(context: Context) {
    requested.set(true)
    if (running.get()) return
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (scheduler.getPendingJob(LIBRARY_PICTURES_JOB_ID) != null) return
    scheduler.schedule(
        JobInfo.Builder(LIBRARY_PICTURES_JOB_ID, ComponentName(context, LibraryPictureJobService::class.java))
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .build(),
    )
}
