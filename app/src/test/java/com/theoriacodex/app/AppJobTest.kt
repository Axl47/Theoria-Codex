package com.theoriacodex.app

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.series.SeriesDownloadScheduler
import com.theoriacodex.app.vision.LibraryJob
import com.theoriacodex.data.repository.CacheSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PlainJobScheduler::class])
class AppJobTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @Test
    fun `every job has its own ID`() {
        val shared = AppJob.entries.groupBy { it.id }.filterValues { it.size > 1 }
        assertEquals(emptyMap<Int, List<AppJob>>(), shared)
    }

    @Test
    fun `scheduling every background lane leaves each one pending beside the others`() = runTest {
        val downloads = SeriesDownloadScheduler(context) { CacheSettings() }
        downloads.schedule(automatic = false)
        downloads.schedule(automatic = true)
        LibraryJob.PICTURES.schedule(context)
        LibraryJob.FRAMES.schedule(context)

        val lanes = listOf(AppJob.USER_DOWNLOAD, AppJob.AUTOMATIC_DOWNLOAD, AppJob.LIBRARY_PICTURES, AppJob.LIBRARY_FRAMES)
        assertEquals(lanes.map { it.id }.toSet(), scheduler.allPendingJobs.map { it.id }.toSet())
        assertTrue(scheduler.getPendingJob(AppJob.USER_DOWNLOAD.id)!!.isUserInitiated)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, scheduler.getPendingJob(AppJob.AUTOMATIC_DOWNLOAD.id)!!.networkType)
        assertTrue(scheduler.getPendingJob(AppJob.LIBRARY_FRAMES.id)!!.isRequireDeviceIdle)
        assertFalse(scheduler.getPendingJob(AppJob.LIBRARY_PICTURES.id)!!.isRequireDeviceIdle)
    }

    @Test
    fun `user downloads the system refuses to start as user-initiated still download in an ordinary job`() = runTest {
        val refusing = RefusingUserInitiated(scheduler)
        val settings = CacheSettings(downloadsOverMetered = true, downloadsOverRoaming = false)
        SeriesDownloadScheduler(refusing.context(context)) { settings }.schedule(automatic = false)

        assertEquals(listOf(true, false), refusing.attempts)
        val job = scheduler.getPendingJob(AppJob.USER_DOWNLOAD.id)!!
        assertFalse(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_NOT_ROAMING, job.networkType)
    }

    @Test
    fun `ensuring a lane schedules it only when it has no pending job`() = runTest {
        val counting = RefusingUserInitiated(scheduler, refuse = false)
        val downloads = SeriesDownloadScheduler(counting.context(context)) { CacheSettings() }
        downloads.schedule(automatic = true)

        downloads.ensureScheduled(automatic = true)
        assertEquals(listOf(AppJob.AUTOMATIC_DOWNLOAD.id), counting.scheduled)

        downloads.ensureScheduled(automatic = false)
        assertEquals(listOf(AppJob.AUTOMATIC_DOWNLOAD.id, AppJob.USER_DOWNLOAD.id), counting.scheduled)
        assertEquals(
            setOf(AppJob.AUTOMATIC_DOWNLOAD.id, AppJob.USER_DOWNLOAD.id),
            scheduler.allPendingJobs.map { it.id }.toSet(),
        )
    }

    private class RefusingUserInitiated(private val real: JobScheduler, private val refuse: Boolean = true) : JobScheduler() {
        val scheduled = mutableListOf<Int>()
        val attempts = mutableListOf<Boolean>()

        fun context(base: Context): Context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? =
                if (name == JOB_SCHEDULER_SERVICE) this@RefusingUserInitiated else super.getSystemService(name)
        }

        override fun schedule(job: JobInfo): Int {
            attempts += job.isUserInitiated
            if (refuse && job.isUserInitiated) throw IllegalArgumentException("Not in view")
            scheduled += job.id
            return real.schedule(job)
        }

        override fun enqueue(job: JobInfo, work: JobWorkItem) = real.enqueue(job, work)
        override fun cancel(jobId: Int) = real.cancel(jobId)
        override fun cancelAll() = real.cancelAll()
        override fun getAllPendingJobs(): List<JobInfo> = real.allPendingJobs
        override fun getPendingJob(jobId: Int): JobInfo? = real.getPendingJob(jobId)
    }
}

@Implements(JobScheduler::class)
class PlainJobScheduler
