package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.FollowedSeries
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
    private val series = Series(
        id, "Series", coverUrl = "https://cdn.test/c.jpg", author = "Author", genres = listOf("Action", "Long Strip"), language = "en",
        memo = """{"slug":"a"}""",
    )

    @After fun close() = database.close()

    @Test
    fun `a stored series reads back as it was stored, unfollowed and unread`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", 2f, "Group", 2_000L, """{"id":2}"""), SeriesEntry("/c/1", "Chapter 1")))

        val stored = repository.series(id)!!
        assertEquals(series, stored.series)
        assertFalse(stored.followed)
        assertNull(stored.readingMode)
        val entries = repository.entries(id)
        assertEquals(listOf("/c/2", "/c/1"), entries.map { it.entry.address })
        assertEquals(SeriesEntry("/c/2", "Chapter 2", 2f, "Group", 2_000L, """{"id":2}"""), entries.first().entry)
        assertTrue(entries.none { it.read })
        assertTrue(repository.observeFollowed().first().isEmpty())
    }

    @Test
    fun `whether any series is followed, and one entry while its source lists it`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        assertFalse(repository.observeHasFollowed().first())
        repository.setFollowed(id, true)
        assertTrue(repository.observeHasFollowed().first())
        repository.setFollowed(id, false)
        assertFalse(repository.observeHasFollowed().first())

        assertEquals("Chapter 1", repository.listedEntry(id, "/c/1")?.entry?.name)
        assertNull(repository.listedEntry(id, "/c/9"))
        repository.storeEntries(id, listOf(SeriesEntry("/c/2", "Chapter 2")))
        assertNull("Dropped by its source", repository.listedEntry(id, "/c/1"))
        assertEquals("Still stored", "Chapter 1", repository.storedEntry(id, "/c/1")?.entry?.name)
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
    fun `a new listing keeps progress, follow, mode and shared first pages, adds new entries and hides dropped ones`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.setFollowed(id, true)
        repository.setReadingMode(id, GalleryReadingMode.VERTICAL)
        repository.setChaptersShareFirstPage(id)
        repository.recordProgress(id, "/c/2", position = 2, length = 2)
        assertEquals(1, repository.observeFollowed().first().single().unreadCount)

        now = 200L
        repository.store(series.copy(title = "Renamed"), listOf(SeriesEntry("/c/3", "Chapter 3"), SeriesEntry("/c/2", "Chapter 2 (v2)")))

        val stored = repository.series(id)!!
        assertEquals("Renamed", stored.series.title)
        assertTrue(stored.followed)
        assertEquals(GalleryReadingMode.VERTICAL, stored.readingMode)
        assertTrue(stored.chaptersShareFirstPage)
        assertEquals("/c/2", stored.lastOpenedEntry)
        val entries = repository.entries(id)
        assertEquals(listOf("/c/3", "/c/2"), entries.map { it.entry.address })
        assertEquals(listOf(200L, 100L), entries.map { it.firstSeenAtEpochMs })
        assertEquals("Chapter 2 (v2)", entries.last().entry.name)
        assertTrue(entries.last().read)
        assertEquals(1, repository.observeFollowed().first().single().unreadCount)
    }

    @Test
    fun `a listing or details without a description, status or memo keep the stored ones, and entries their memos`() = runTest {
        val detailed = series.copy(description = "A synopsis.", status = SeriesStatus.COMPLETED)
        repository.store(detailed)
        assertEquals(detailed, repository.series(id)!!.series)

        repository.store(series.copy(title = "Renamed", memo = null))
        assertEquals(detailed.copy(title = "Renamed"), repository.series(id)!!.series)

        // So does an entry listed without its memo, while one listed with a memo replaces it.
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", memo = """{"id":2}"""), SeriesEntry("/c/1", "Chapter 1", memo = """{"id":1}""")))
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1", memo = """{"id":"1b"}""")))
        assertEquals(listOf("""{"id":2}""", """{"id":"1b"}"""), repository.entries(id).map { it.entry.memo })

        repository.store(series.copy(description = "Rewritten.", status = SeriesStatus.ON_HIATUS, memo = """{"slug":"b"}"""))
        assertEquals("Rewritten.", repository.series(id)!!.series.description)
        assertEquals(SeriesStatus.ON_HIATUS, repository.series(id)!!.series.status)
        assertEquals("""{"slug":"b"}""", repository.series(id)!!.series.memo)
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
    fun `a listing that matches no stored chapter is a baseline with nothing new, and dropped chapters keep progress`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.recordProgress(id, "/c/1", position = 3, length = 3)

        // Every address changes, and no chapter carries a number to match it as moved.
        val relisted = listOf(SeriesEntry("/v2/3", "Chapter 3"), SeriesEntry("/v2/2", "Chapter 2"), SeriesEntry("/v2/1", "Chapter 1"))
        assertEquals(emptyList<SeriesEntry>(), repository.store(series, relisted))
        assertEquals(relisted.map { it.address }, repository.entries(id).map { it.entry.address })

        // The old chapter kept its row and progress: listed again, it is neither new nor unread.
        assertEquals(emptyList<SeriesEntry>(), repository.store(series, relisted + SeriesEntry("/c/1", "Chapter 1")))
        assertTrue(repository.entries(id).single { it.entry.address == "/c/1" }.read)
    }

    @Test
    fun `one address or moved chapter shared with the stored listing lets the chapters beyond it through as new`() = runTest {
        val first = SeriesEntry("/c/1", "Chapter 1", number = 1f)
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), first))

        val arrived = listOf(SeriesEntry("/v2/4", "Chapter 4"), SeriesEntry("/v2/3", "Chapter 3"))
        assertEquals(arrived, repository.store(series, arrived + first))

        // Only a moved chapter matches: the rest is still new.
        val later = listOf(SeriesEntry("/v3/5", "Chapter 5"))
        assertEquals(later, repository.store(series, later + SeriesEntry("/v3/1", "Chapter 1", number = 1f)))
    }

    @Test
    fun `another group's release of a number, or one of several, is a new unread chapter`() = runTest {
        val read = SeriesEntry("/c/1-a", "Chapter 1", number = 1f, translatorGroup = "A")
        val prologue = SeriesEntry("/c/0", "Prologue")
        repository.store(series, listOf(read, prologue))
        repository.recordProgress(id, "/c/1-a", position = 4, length = 4)

        // Group A's release goes; group B's and two of a group that never released it arrive, and an unnumbered one.
        val arrived = listOf(
            SeriesEntry("/c/1-b", "Chapter 1", number = 1f, translatorGroup = "B"),
            SeriesEntry("/c/1-c", "Chapter 1", number = 1f, translatorGroup = "C"),
            SeriesEntry("/c/1-c2", "Chapter 1 v2", number = 1f, translatorGroup = "C"),
            SeriesEntry("/c/extra", "Extra"),
        )
        assertEquals(arrived, repository.store(series, arrived + prologue))
        assertEquals(List(5) { false }, repository.entries(id).map { it.read })
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
    fun `a followed series' first listing reports only chapters dated after it was followed, less a day`() = runTest {
        val day = 24 * 60 * 60 * 1_000L
        val fresh = SeriesEntry("/c/3", "Chapter 3", uploadedAtEpochMs = 10 * day)
        val sameDay = SeriesEntry("/c/2", "Chapter 2", uploadedAtEpochMs = 9 * day + 1)
        val old = listOf(SeriesEntry("/c/1", "Chapter 1", uploadedAtEpochMs = day), SeriesEntry("/c/0", "Chapter 0"))
        repository.store(series)
        now = 10 * day
        repository.setFollowed(id, true)
        repository.storeEntries(id, emptyList())

        assertEquals(listOf(fresh, sameDay), repository.storeEntries(id, listOf(fresh, sameDay) + old))

        val unfollowed = Series(SeriesId(id.source, "/series/b"), "Unfollowed", language = "en")
        repository.store(unfollowed)
        assertEquals(emptyList<SeriesEntry>(), repository.storeEntries(unfollowed.id, listOf(fresh)))
    }

    @Test
    fun `an empty listing and a details-only store leave the entries alone`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/1", "Chapter 1")))

        repository.store(series, emptyList())
        repository.store(series.copy(author = "Other"))

        assertEquals(1, repository.entries(id).size)
        assertEquals("Other", repository.series(id)!!.series.author)
    }

    @Test
    fun `marking all chapters read or unread updates listed chapters and resets progress when unread`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.setFollowed(id, true)
        repository.recordProgress(id, "/c/1", position = 5, length = 10)

        repository.setAllRead(id, read = true)
        assertTrue(repository.entries(id).all { it.read })
        assertEquals(0, repository.observeFollowed().first().single().unreadCount)

        repository.setAllRead(id, read = false)
        assertTrue(repository.entries(id).none { it.read })
        assertTrue(repository.entries(id).all { it.position == 0L })
        assertNull(repository.series(id)!!.lastOpenedEntry)
        assertEquals(2, repository.observeFollowed().first().single().unreadCount)
    }

    @Test
    fun `setEntriesRead and restoreProgress update targeted chapters and preserve unrelated edits on undo`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/3", "Chapter 3"), SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))
        repository.recordProgress(id, "/c/1", position = 4, length = 10)

        val restore = repository.setEntriesRead(id, listOf("/c/2", "/c/1"), read = true)
        assertEquals(listOf(false, true, true), repository.entries(id).map { it.read })

        // Separate edit to an unrelated chapter after the bulk action.
        repository.setEntriesRead(id, listOf("/c/3"), read = true)

        repository.restoreProgress(id, restore)
        val restored = repository.entries(id)
        assertEquals(listOf(true, false, false), restored.map { it.read })
        assertEquals(4L, restored.last().position)
        assertEquals("/c/1", repository.series(id)!!.lastOpenedEntry)

        val unreadRestore = repository.setEntriesRead(id, listOf("/c/1"), read = false)
        assertEquals(0L, repository.entries(id).last().position)
        assertNull(repository.series(id)!!.lastOpenedEntry)
        repository.restoreProgress(id, unreadRestore)
        assertEquals(4L, repository.entries(id).last().position)
        assertEquals("/c/1", repository.series(id)!!.lastOpenedEntry)

        // If another chapter was opened after Mark all unread, Undo keeps that newer Continue destination.
        val allUnreadRestore = repository.setAllRead(id, read = false)
        repository.recordProgress(id, "/c/3", position = 2, length = 8)
        repository.restoreProgress(id, allUnreadRestore)
        assertEquals("/c/3", repository.series(id)!!.lastOpenedEntry)
    }

    @Test
    fun `observeFollowed puts series with unread chapters before caught-up ones`() = runTest {
        val caughtUpId = SeriesId(id.source, "/series/alpha")
        val unreadId = SeriesId(id.source, "/series/zeta")
        repository.store(series.copy(id = caughtUpId, title = "Alpha"), listOf(SeriesEntry("/a/1", "Ch 1")))
        repository.store(series.copy(id = unreadId, title = "Zeta"), listOf(SeriesEntry("/z/2", "Ch 2"), SeriesEntry("/z/1", "Ch 1")))
        repository.setFollowed(caughtUpId, true)
        repository.setFollowed(unreadId, true)
        repository.setAllRead(caughtUpId, read = true)

        assertEquals(listOf("Zeta", "Alpha"), repository.observeFollowed().first().map { it.series.title })
    }

    @Test
    fun `the Following list says when a series was followed, read, last updated and whether it was started`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2", uploadedAtEpochMs = 50L), SeriesEntry("/c/1", "Chapter 1", uploadedAtEpochMs = 20L)))
        now = 200L
        repository.setFollowed(id, true)
        repository.setFollowed(id, true)
        assertEquals(FollowedSeries(series, 2, followedAtEpochMs = 200L, lastUpdatedAtEpochMs = 50L), repository.observeFollowed().first().single())

        // Opening an entry stamps it; reopening it where it was left does not, and moving on does.
        now = 300L
        repository.recordProgress(id, "/c/1", position = 1, length = 4)
        now = 400L
        repository.recordProgress(id, "/c/1", position = 1, length = 4)
        assertEquals(300L to true, repository.observeFollowed().first().single().let { it.lastReadAtEpochMs to it.started })
        now = 500L
        repository.recordProgress(id, "/c/1", position = 2, length = 4)
        assertEquals(500L, repository.observeFollowed().first().single().lastReadAtEpochMs)
        // Marking an entry read is not reading it.
        now = 600L
        repository.setAllRead(id, read = true)
        assertEquals(listOf(null, 500L), repository.entries(id).map { it.lastReadAtEpochMs })

        repository.setFollowed(id, false)
        assertNull(repository.series(id)!!.followedAtEpochMs)
    }

    @Test
    fun `an entry without an upload date counts as updated when it was first seen`() = runTest {
        repository.store(series, listOf(SeriesEntry("/c/1", "Chapter 1", uploadedAtEpochMs = 20L)))
        repository.setFollowed(id, true)
        now = 500L
        repository.store(series, listOf(SeriesEntry("/c/2", "Chapter 2"), SeriesEntry("/c/1", "Chapter 1")))

        assertEquals(500L, repository.observeFollowed().first().single().lastUpdatedAtEpochMs)
    }
}
