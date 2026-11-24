package com.theoriacodex.app

import android.app.job.JobInfo
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.theoriacodex.app.series.SeriesDownloadJobService
import com.theoriacodex.app.series.SeriesUpdateJobService
import com.theoriacodex.app.vision.LibraryFrameJobService
import com.theoriacodex.app.vision.LibraryPictureJobService

internal enum class AppJob(val id: Int, private val service: Class<out JobService>) {
    SERIES_UPDATE(3101, SeriesUpdateJobService::class.java),

    USER_DOWNLOAD(3102, SeriesDownloadJobService::class.java),

    AUTOMATIC_DOWNLOAD(3103, SeriesDownloadJobService::class.java),

    LIBRARY_PICTURES(3104, LibraryPictureJobService::class.java),

    LIBRARY_FRAMES(3105, LibraryFrameJobService::class.java),
    ;

    fun builder(context: Context) = JobInfo.Builder(id, ComponentName(context, service))
}
