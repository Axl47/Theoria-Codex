package com.theoriacodex.app.series

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SeriesUpdateScheduleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val one = listOf(FollowedSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/series/a"), "Series"), unreadCount = 0))
    private val followed = MutableStateFlow(emptyList<FollowedSeries>())
    private val settings = MutableStateFlow(AppSettings())

    private fun job(): JobInfo? = scheduler.getPendingJob(SERIES_UPDATE_JOB_ID)

    @Test
    fun `the check is scheduled only while a series is followed and the setting is on`() = runTest(UnconfinedTestDispatcher()) {
        val keeping = launch { keepSeriesUpdatesScheduled(context, followed, settings) }
        assertNull(job())

        followed.value = one
        val scheduled = job()!!
        assertEquals(12.hours.inWholeMilliseconds, scheduled.intervalMillis)
        assertTrue(scheduled.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, scheduled.networkType)

        settings.value = AppSettings(seriesUpdateInterval = SeriesUpdateInterval.HOURS_6)
        assertEquals(6.hours.inWholeMilliseconds, job()!!.intervalMillis)

        settings.value = AppSettings(seriesUpdateInterval = SeriesUpdateInterval.OFF)
        assertNull(job())

        settings.value = AppSettings()
        followed.value = emptyList()
        assertNull(job())
        keeping.cancel()
    }

    @Test
    fun `a notification names the one new chapter or counts several`() {
        assertEquals("Chapter 5", newChaptersText(listOf(SeriesEntry("/c/5", "Chapter 5"))))
        assertEquals("2 new chapters", newChaptersText(listOf(SeriesEntry("/c/6", "Chapter 6"), SeriesEntry("/c/5", "Chapter 5"))))
    }

    @Test
    fun `a notification's address names its series`() {
        val id = one.single().series.id
        assertEquals(id, seriesIdOfUri(seriesUri(id)))
    }
}
