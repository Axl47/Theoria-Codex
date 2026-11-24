package com.theoriacodex.app.series

import android.app.job.JobParameters
import android.app.job.JobService
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The background check for new chapters and episodes, run by `JobScheduler` on [scheduleSeriesUpdates]' terms. It loads the
 * extensions the way the Series tab does, stores the new chapters of the followed series due a check by their release
 * pace, and announces each series as soon as its chapters are stored, so a run the system stops midway has announced
 * what it stored.
 */
class SeriesUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        check = scope.launch {
            runCatchingExceptionsPreservingCancellation {
                val container = (application as TheoriaAppContainerOwner).awaitAppContainer()
                val runEvery = container.data.settingsRepository.observeSettings().first().seriesUpdateInterval.hours?.hours
                val started = TimeSource.Monotonic.markNow()
                val check = runEvery?.let { interval ->
                    container.series?.checkFollowed(interval) { notifySeriesUpdate(this@SeriesUpdateJobService, it) }
                }
                if (check != null) {
                    Diagnostics.report(
                        "series",
                        "Checked ${check.checked} followed series in ${started.elapsedNow().inWholeSeconds} s, " +
                            "${check.notDue} not due: ${check.updates.size} with new chapters or episodes, ${check.failed} failed",
                    )
                }
            }.onFailure { error ->
                // Saved data that cannot open, say: the app's own start shows that failure, and the next period tries again.
                Diagnostics.report("series", "The check for new chapters and episodes failed", error)
            }
            jobFinished(params, false)
        }
        return true
    }

    /** The system took the job back (the network went away, say); the next period checks again. */
    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
