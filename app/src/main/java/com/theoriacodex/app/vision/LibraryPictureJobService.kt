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

internal enum class LibraryJob(private val job: AppJob) {
    PICTURES(AppJob.LIBRARY_PICTURES),

    FRAMES(AppJob.LIBRARY_FRAMES),
    ;

    val running = AtomicBoolean(false)
    val requested = AtomicBoolean(false)

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

abstract class LibraryJobService internal constructor(private val job: LibraryJob) : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null

    protected abstract suspend fun work(pictures: AppPictures): Boolean

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
            job.running.set(false)
            jobFinished(params, more || job.requested.getAndSet(false))
        }
        return true
    }

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

class LibraryPictureJobService : LibraryJobService(LibraryJob.PICTURES) {
    override val failure = "The Library's pictures could not be indexed"

    override suspend fun work(pictures: AppPictures): Boolean =
        pictures.indexLibrary().also { LibraryJob.FRAMES.schedule(this) }
}

class LibraryFrameJobService : LibraryJobService(LibraryJob.FRAMES) {
    override val failure = "The Library's video frames could not be read"

    override suspend fun work(pictures: AppPictures): Boolean = pictures.readFrames()
}

internal suspend fun keepLibraryPicturesScheduled(context: Context, pictures: AppPictures) {
    val model = pictures.model.states.filter { it == ModelState.Absent || it == ModelState.Ready }.distinctUntilChanged()
    combine(model, pictures.progress.map { it.total }.distinctUntilChanged()) { _, _ -> }
        .collect { LibraryJob.entries.forEach { it.schedule(context) } }
}
