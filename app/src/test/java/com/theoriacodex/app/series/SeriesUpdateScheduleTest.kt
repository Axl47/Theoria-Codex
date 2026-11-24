package com.theoriacodex.app.series

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.AppJob
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ChapterName
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.data.repository.SeriesUpdate
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
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SeriesUpdateScheduleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val one = listOf(FollowedSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/series/a"), "Series"), unreadCount = 0))
    private val followed = MutableStateFlow(emptyList<FollowedSeries>())
    private val settings = MutableStateFlow(AppSettings())

    private fun job(): JobInfo? = scheduler.getPendingJob(AppJob.SERIES_UPDATE.id)

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
    fun `a notification names the one new entry or counts several as chapters or episodes`() {
        val manga = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/manga/a"), "Manga")
        val anime = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_1"), "/anime/a"), "Anime")
        val two = listOf(SeriesEntry("/e/6", "6"), SeriesEntry("/e/5", "5"))

        assertEquals("Chapter 5", newEntriesText(SeriesUpdate(manga, listOf(SeriesEntry("/c/5", "Chapter 5")))))
        assertEquals("2 new chapters", newEntriesText(SeriesUpdate(manga, two)))
        assertEquals("2 new episodes", newEntriesText(SeriesUpdate(anime, two)))
    }

    @Test
    fun `series announced by separate checks are grouped under one summary`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val other = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/series/b"), "Other")
        fun summaries() = manager.activeNotifications.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }

        notifySeriesUpdate(context, SeriesUpdate(one.single().series, listOf(SeriesEntry("/c/5", "Chapter 5"))))
        assertEquals(0, summaries())
        notifySeriesUpdate(context, SeriesUpdate(other, listOf(SeriesEntry("/c/2", "Chapter 2"))))
        assertEquals(1, summaries())
        assertEquals(3, manager.activeNotifications.size)
    }

    @Test
    fun `a failed download names its one chapter and opens its series, and across series counts them`() {
        val id = one.single().series.id
        val other = SeriesId(SourceKey.parseOrThrow("custom_mihon_2"), "/series/b")
        notifyDownloadsFailed(context, listOf(ChapterName(id, "Title", "Chapter 5")))

        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("Title", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Chapter 5 could not be downloaded", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(id, seriesIdOfUri(checkNotNull(shadowOf(posted.contentIntent).savedIntent.data)))
        val across = listOf(ChapterName(id, "Title", "Chapter 5"), ChapterName(other, "Other", "Chapter 2"))
        assertEquals(ChaptersNotice("2 chapters could not be downloaded", null, null), failedNotice(across))
        assertEquals(ChaptersNotice("Title", "2 chapters waiting · Extension unavailable", id), waitingNotice(List(2) { across.first() }))
    }

    @Test
    fun `a notification's address names its series`() {
        val id = one.single().series.id
        assertEquals(id, seriesIdOfUri(seriesUri(id)))
    }
}
