package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
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
    private val series = Series(id, "Series", coverUrl = "https://cdn.test/c.jpg", author = "Author", genres = listOf("Action", "Long Strip"), language = "en")

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

        repository.recordProgress(id, "/c/1", position = 1, length = null)
        assertEquals("/c/1", repository.series(id)!!.lastOpenedEntry)
        assertEquals(0, repository.entries(id).last().position)

        repository.recordProgress(id, "/c/1", position = 3, length = 4)
        assertEquals(3, repository.entries(id).last().position)
        assertFalse(repository.entries(id).last().read)

        repository.recordProgress(id, "/c/1", position = 4, length = 4)
        assertTrue(repository.entries(id).last().read)
        assertFalse(repository.entries(id).first().read)
    }

    @Test
    fun `an anime series' episode is watched from 85 percent of its duration, its source telling the kind`() = runTest {
        val anime = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_1"), "/anime/a"), "Anime")
        repository.store(anime, listOf(SeriesEntry("/e/1", "Episode 1")))

        repository.recordProgress(anime.id, "/e/1", position = 1_000_000, length = 1_440_000)
        assertEquals(1_000_000L to false, repository.entries(anime.id).single().let { it.position to it.read })

        repository.recordProgress(anime.id, "/e/1", position = 1_224_000, length = 1_440_000)
        assertTrue(repository.entries(anime.id).single().read)
    }

    @Test
    fun `a new listing keeps progress, follow and mode, adds new entries and hides dropped ones`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.setFollowed(id, true)
        repository.setReadingMode(id, GalleryReadingMode.VERTICAL)
        repository.recordProgress(id, "/c/2", position = 2, length = 2)
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
    fun `a listing or details without a description or status keep the stored ones`() = runTest {
        val detailed = series.copy(description = "A synopsis.", status = SeriesStatus.COMPLETED)
        repository.store(detailed)
        assertEquals(detailed, repository.series(id)!!.series)

        repository.store(series.copy(title = "Renamed"))
        assertEquals(detailed.copy(title = "Renamed"), repository.series(id)!!.series)

        repository.store(series.copy(description = "Rewritten.", status = SeriesStatus.ON_HIATUS))
        assertEquals("Rewritten.", repository.series(id)!!.series.description)
        assertEquals(SeriesStatus.ON_HIATUS, repository.series(id)!!.series.status)
    }

    @Test
    fun `a dropped chapter that returns is neither new nor unread`() = runTest {
        val first = SeriesEntry("/c/1", "Chapter 1")
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), first))
        repository.recordProgress(id, "/c/2", position = 2, length = 2)

        assertEquals(emptyList<SeriesEntry>(), repository.store(series, listOf(first)))
        assertEquals(listOf("/c/1"), repository.entries(id).map { it.entry.address })

        assertEquals(emptyList<SeriesEntry>(), repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), first)))
        assertEquals(listOf(true, false), repository.entries(id).map { it.read })
    }

    @Test
    fun `a chapter that returns at a new address takes over its number's progress and is not new`() = runTest {
        val first = SeriesEntry("/c/1", "Chapter 1", number = 1f)
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", number = 2f), first))
        repository.recordProgress(id, "/c/2", position = 3, length = 5)

        val reuploaded = SeriesEntry("/c/2-v2", "Chapter 2", number = 2f)
        val third = SeriesEntry("/c/3", "Chapter 3", number = 3f)
        assertEquals(listOf(third), repository.store(series, listOf(third, reuploaded, first)))

        val moved = repository.entries(id).single { it.entry.address == "/c/2-v2" }
        assertEquals(3, moved.position)
        assertEquals(5L, moved.length)
        assertEquals("/c/2-v2", repository.series(id)!!.lastOpenedEntry)

        // The old address is remembered: listed again beside its replacement, it is neither new nor unread.
        repository.recordProgress(id, "/c/2-v2", position = 5, length = 5)
        val both = listOf(third, reuploaded, SeriesEntry("/c/2", "Chapter 2", number = 2f), first)
        assertEquals(emptyList<SeriesEntry>(), repository.store(series, both))
        assertEquals(listOf(3L, 5L), listOf("/c/2", "/c/2-v2").map { address -> repository.entries(id).single { it.entry.address == address }.position })
    }

    @Test
    fun `another group's release of a number, or one of several, is a new unread chapter`() = runTest {
        val read = SeriesEntry("/c/1-a", "Chapter 1", number = 1f, translatorGroup = "A")
        repository.store(series, listOf(read, SeriesEntry("/c/0", "Prologue")))
        repository.recordProgress(id, "/c/1-a", position = 4, length = 4)

        // Group A's release goes; group B's and two of a group that never released it arrive, and an unnumbered one.
        val arrived = listOf(
            SeriesEntry("/c/1-b", "Chapter 1", number = 1f, translatorGroup = "B"),
            SeriesEntry("/c/1-c", "Chapter 1", number = 1f, translatorGroup = "C"),
            SeriesEntry("/c/1-c2", "Chapter 1 v2", number = 1f, translatorGroup = "C"),
            SeriesEntry("/c/extra", "Extra"),
        )
        assertEquals(arrived, repository.store(series, arrived))
        assertEquals(List(4) { false }, repository.entries(id).map { it.read })
        assertEquals("/c/1-a", repository.series(id)!!.lastOpenedEntry)
    }

    @Test
    fun `a kept address renumbered to a dropped chapter's number keeps that chapter's progress from a new address`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", number = 2f), SeriesEntry("/c/1", "Chapter 1", number = 1f)))
        repository.recordProgress(id, "/c/1", position = 3, length = 3)

        // Chapter 1's address goes; the kept address now says it is chapter 1, and a new address says so too.
        val added = SeriesEntry("/c/1-v2", "Chapter 1", number = 1f)
        assertEquals(listOf(added), repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 1", number = 1f), added)))
        assertEquals(listOf(false, false), repository.entries(id).map { it.read })
    }

    @Test
    fun `storing entries alone leaves what is stored about the series as it is`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/1", "Chapter 1")))
        repository.store(series.copy(title = "Renamed", description = "A synopsis."))

        val second = SeriesEntry("/c/2", "Chapter 2")
        assertEquals(listOf(second), repository.storeEntries(id, listOf(second, SeriesEntry("/c/1", "Chapter 1"))))
        assertEquals(series.copy(title = "Renamed", description = "A synopsis."), repository.series(id)!!.series)
        assertEquals(emptyList<SeriesEntry>(), repository.storeEntries(id.copy(address = "/series/unknown"), listOf(second)))
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
