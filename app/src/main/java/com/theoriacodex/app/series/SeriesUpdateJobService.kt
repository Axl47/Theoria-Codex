package com.theoriacodex.app.series

import android.app.job.JobParameters
import android.app.job.JobService
import com.theoriacodex.app.di.TheoriaAppContainerOwner
import com.theoriacodex.domain.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The background check for new chapters, run by `JobScheduler` on [scheduleSeriesUpdates]' terms. It loads the
 * extensions the way the Series tab does, stores the followed series' new chapters, and announces each series as soon
 * as its chapters are stored, so a run the system stops midway has announced what it stored.
 */
class SeriesUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        check = scope.launch {
            try {
                val series = (application as TheoriaAppContainerOwner).awaitAppContainer().series
                val updates = series?.checkFollowed { notifySeriesUpdate(this@SeriesUpdateJobService, it) }.orEmpty()
                Diagnostics.report("series", "Checked followed series: ${updates.size} with new chapters")
            } catch (error: CancellationException) {
                // onStopJob ended this run; the job is not reported finished a second time.
                throw error
            } catch (error: Exception) {
                // Saved data that cannot open, say: the app's own start shows that failure, and the next period tries again.
                Diagnostics.report("series", "The check for new chapters failed", error)
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
