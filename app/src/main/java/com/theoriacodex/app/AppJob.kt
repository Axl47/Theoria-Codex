package com.theoriacodex.app

import android.app.job.JobInfo
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.theoriacodex.app.series.SeriesDownloadJobService
import com.theoriacodex.app.series.SeriesUpdateJobService
import com.theoriacodex.app.vision.LibraryFrameJobService
import com.theoriacodex.app.vision.LibraryPictureJobService

/**
 * Every `JobScheduler` job the app schedules: its ID and the service that runs it. IDs are unique per app: scheduling
 * one replaces any pending or running job with the same ID, whatever its service. Persisted jobs keep their IDs across
 * updates, so never renumber or reuse one; give a new job the next free number here and build it with [builder].
 * Scripts that drive jobs by number name them too.
 */
internal enum class AppJob(val id: Int, private val service: Class<out JobService>) {
    /** The periodic check for new chapters and episodes. */
    SERIES_UPDATE(3101, SeriesUpdateJobService::class.java),

    /** Chapter downloads the user asked for. */
    USER_DOWNLOAD(3102, SeriesDownloadJobService::class.java),

    /** Chapter downloads the check for new chapters queued. */
    AUTOMATIC_DOWNLOAD(3103, SeriesDownloadJobService::class.java),

    /** Hashing and tagging the Library's pictures from their stills. */
    LIBRARY_PICTURES(3104, LibraryPictureJobService::class.java),

    /** Reading playing posts' frames while the device is idle. */
    LIBRARY_FRAMES(3105, LibraryFrameJobService::class.java),
    ;

    /** Starts this job's [JobInfo], with its ID and service; the caller adds its conditions. */
    fun builder(context: Context) = JobInfo.Builder(id, ComponentName(context, service))
}
