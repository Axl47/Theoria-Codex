package com.theoriacodex.app.vision

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.Context
import com.theoriacodex.app.AppJob
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Library's background picture work, as `JobScheduler` knows it. Both jobs run while the device charges on an
 * unmetered network with its battery not low, since a run may fetch previews the cache lost or video frames, and both
 * survive a reboot.
 */
internal enum class LibraryJob(private val job: AppJob) {
    /** Hashes and tags the Library's pictures from their stills ([LibraryPictureJobService]). */
    PICTURES(AppJob.LIBRARY_PICTURES),

    /**
     * Upgrades playing posts' still analyses with their frames ([LibraryFrameJobService]), only while the device is idle:
     * nobody is using it, so no card plays and no Like tap waits on the decoders.
     */
    FRAMES(AppJob.LIBRARY_FRAMES),
    ;

    /** Whether a run is under way in this process, and whether work arrived during it. */
    val running = AtomicBoolean(false)
    val requested = AtomicBoolean(false)

    /**
     * Asks for this job to run on its terms. A job already waiting is left alone and a running one is asked to run
     * again, so calling this whenever the Library or the model changes costs nothing.
     */
    fun schedule(context: Context) {
        requested.set(true)
        if (running.get()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(job.id) != null) return
        scheduler.schedule(
            job.builder(context)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresDeviceIdle(this == FRAMES)
                .setPersisted(true)
                .build(),
        )
    }
}

/**
 * Runs one [LibraryJob]'s [work] in the app's container. When the work asks to run again (the device ran warm, say), or
 * work arrived during the run, it asks the system for another run; the system stops it when its conditions end
 * (unplugged, or the device left idle) and runs it again when they return.
 */
abstract class LibraryJobService internal constructor(private val job: LibraryJob) : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null

    /** This job's work; returns whether it should run again soon. */
    protected abstract suspend fun work(pictures: AppPictures): Boolean

    /** What [Diagnostics] reports when [work] fails. */
    protected abstract val failure: String

    override fun onStartJob(params: JobParameters): Boolean {
        job.running.set(true)
        job.requested.set(false)
        run = scope.launch {
            val more = runCatchingExceptionsPreservingCancellation {
                (application as TheoriaAppContainerOwner).awaitAppContainer().pictures?.let { work(it) } ?: false
            }.getOrElse { error ->
                Diagnostics.report("similarity", failure, error)
                false
            }
            // Work that arrived during the run (a post saved while charging) gets a run of its own.
            job.running.set(false)
            jobFinished(params, more || job.requested.getAndSet(false))
        }
        return true
    }

    /** The conditions ended: the next time they hold picks up where this run stopped. */
    override fun onStopJob(params: JobParameters): Boolean {
        job.running.set(false)
        run?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Hashes and tags the Library's pictures from their stills ([AppPictures.indexLibrary]), then asks for their frames. */
class LibraryPictureJobService : LibraryJobService(LibraryJob.PICTURES) {
    override val failure = "The Library's pictures could not be indexed"

    override suspend fun work(pictures: AppPictures): Boolean =
        pictures.indexLibrary().also { LibraryJob.FRAMES.schedule(this) }
}

/** Upgrades playing posts' still analyses with their frames ([AppPictures.readFrames]) while the device is idle. */
class LibraryFrameJobService : LibraryJobService(LibraryJob.FRAMES) {
    override val failure = "The Library's video frames could not be read"

    override suspend fun work(pictures: AppPictures): Boolean = pictures.readFrames()
}

/**
 * Keeps both [LibraryJob]s scheduled for [pictures]: at every start, whenever the model comes or goes, and whenever the
 * Library gains or loses a post. Runs until cancelled.
 */
internal suspend fun keepLibraryPicturesScheduled(context: Context, pictures: AppPictures) {
    val model = pictures.model.states.filter { it == ModelState.Absent || it == ModelState.Ready }.distinctUntilChanged()
    combine(model, pictures.progress.map { it.total }.distinctUntilChanged()) { _, _ -> }
        .collect { LibraryJob.entries.forEach { it.schedule(context) } }
}
