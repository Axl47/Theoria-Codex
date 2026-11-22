package com.theoriacodex.app.series

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FollowedSeries
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The background check for new chapters, as `JobScheduler` knows it. */
internal const val SERIES_UPDATE_JOB_ID = 3101

/**
 * Keeps the background check scheduled exactly while a series is followed and the interval setting is on, and
 * cancelled otherwise. Runs for as long as it is collected.
 */
internal suspend fun keepSeriesUpdatesScheduled(context: Context, followed: Flow<List<FollowedSeries>>, settings: Flow<AppSettings>) {
    combine(followed, settings) { series, current -> current.seriesUpdateInterval.hours.takeIf { series.isNotEmpty() } }
        .distinctUntilChanged()
        .collect { hours -> scheduleSeriesUpdates(context, hours) }
}

/**
 * Schedules [SeriesUpdateJobService] every [intervalHours] on any network, kept across reboots; null cancels it. The
 * build's manifest must declare the service and `RECEIVE_BOOT_COMPLETED`, which only builds with extensions do.
 */
internal fun scheduleSeriesUpdates(context: Context, intervalHours: Int?) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (intervalHours == null) return scheduler.cancel(SERIES_UPDATE_JOB_ID)
    val period = intervalHours.hours.inWholeMilliseconds
    // Scheduling again restarts the wait, so a job already running this often is left alone at every app start.
    if (scheduler.getPendingJob(SERIES_UPDATE_JOB_ID)?.intervalMillis == period) return
    scheduler.schedule(
        JobInfo.Builder(SERIES_UPDATE_JOB_ID, ComponentName(context, SeriesUpdateJobService::class.java))
            .setPeriodic(period)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build(),
    )
}
