package com.theoriacodex.app.series

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import com.theoriacodex.app.AppJob
import com.theoriacodex.data.repository.AppSettings
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the background check scheduled exactly while a series is followed and the interval setting is on, and
 * cancelled otherwise. Runs for as long as it is collected.
 */
internal suspend fun keepSeriesUpdatesScheduled(context: Context, anyFollowed: Flow<Boolean>, settings: Flow<AppSettings>) {
    combine(anyFollowed, settings) { followed, current -> current.seriesUpdateInterval.hours.takeIf { followed } }
        .distinctUntilChanged()
        .collect { hours -> scheduleSeriesUpdates(context, hours) }
}

/**
 * Schedules [SeriesUpdateJobService] every [intervalHours] on any network while the battery is not low, kept across
 * reboots; null cancels it. The manifest declares the service and `RECEIVE_BOOT_COMPLETED` for that.
 */
internal fun scheduleSeriesUpdates(context: Context, intervalHours: Int?) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (intervalHours == null) return scheduler.cancel(AppJob.SERIES_UPDATE.id)
    val period = intervalHours.hours.inWholeMilliseconds
    // Scheduling again restarts the wait, so a job already running this often is left alone at every app start.
    val pending = scheduler.getPendingJob(AppJob.SERIES_UPDATE.id)
    if (pending?.intervalMillis == period && pending.isRequireBatteryNotLow) return
    scheduler.schedule(
        AppJob.SERIES_UPDATE.builder(context)
            .setPeriodic(period)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build(),
    )
}
