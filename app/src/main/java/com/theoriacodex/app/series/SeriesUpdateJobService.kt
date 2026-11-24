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
                Diagnostics.report("series", "The check for new chapters and episodes failed", error)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
