package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSeriesRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()
    private var now = 100L
    private val repository = RoomSeriesRepository(database) { now }
    private val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/series/a")
    private val series = Series(id, "Series", coverUrl = "https://cdn.test/c.jpg#mihon=1", author = "Author", genres = listOf("Action", "Long Strip"), language = "en")

    @After fun close() = database.close()

    @Test
    fun `a stored series reads back as it was stored, unfollowed and unread`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", 2f, "Group", 2_000L), SeriesEntry("/c/1", "Chapter 1")))

        val stored = repository.series(id)!!
        assertEquals(series, stored.series)
        assertFalse(stored.followed)
        assertNull(stored.readingMode)
        assertEquals(100L, stored.lastCheckedAtEpochMs)
        val entries = repository.entries(id)
        assertEquals(listOf("/c/2", "/c/1"), entries.map { it.entry.address })
        assertEquals(SeriesEntry("/c/2", "Chapter 2", 2f, "Group", 2_000L), entries.first().entry)
        assertTrue(entries.none { it.read })
        assertTrue(repository.observeFollowed().first().isEmpty())
    }

    @Test
    fun `progress marks the entry opened last, and an entry read at its last page`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))

        repository.recordProgress(id, "/c/1", page = 1, pageCount = null)
        assertEquals("/c/1", repository.series(id)!!.lastOpenedEntry)
        assertEquals(0, repository.entries(id).last().lastPage)

        repository.recordProgress(id, "/c/1", page = 3, pageCount = 4)
        assertEquals(3, repository.entries(id).last().lastPage)
        assertFalse(repository.entries(id).last().read)

        repository.recordProgress(id, "/c/1", page = 4, pageCount = 4)
        assertTrue(repository.entries(id).last().read)
        assertFalse(repository.entries(id).first().read)
    }

    @Test
    fun `a new listing keeps progress, follow and mode, adds new entries and forgets dropped ones`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.setFollowed(id, true)
        repository.setReadingMode(id, GalleryReadingMode.VERTICAL)
        repository.recordProgress(id, "/c/2", page = 2, pageCount = 2)
        assertEquals(1, repository.observeFollowed().first().single().unreadCount)

        now = 200L
        repository.store(series.copy(title = "Renamed"), listOf(SeriesEntry("/c/3", "Chapter 3"), SeriesEntry("/c/2", "Chapter 2 (v2)")))

        val stored = repository.series(id)!!
        assertEquals("Renamed", stored.series.title)
        assertTrue(stored.followed)
        assertEquals(GalleryReadingMode.VERTICAL, stored.readingMode)
        assertEquals("/c/2", stored.lastOpenedEntry)
        val entries = repository.entries(id)
        assertEquals(listOf("/c/3", "/c/2"), entries.map { it.entry.address })
        assertEquals(listOf(200L, 100L), entries.map { it.firstSeenAtEpochMs })
        assertEquals("Chapter 2 (v2)", entries.last().entry.name)
        assertTrue(entries.last().read)
        assertEquals(1, repository.observeFollowed().first().single().unreadCount)
    }

    @Test
    fun `an empty listing and a details-only store leave the entries alone`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/1", "Chapter 1")))

        repository.store(series, emptyList())
        repository.store(series.copy(author = "Other"))

        assertEquals(1, repository.entries(id).size)
        assertEquals("Other", repository.series(id)!!.series.author)
    }
}
