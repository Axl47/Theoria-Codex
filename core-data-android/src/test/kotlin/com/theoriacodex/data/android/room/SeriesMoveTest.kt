package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.data.repository.switchSource
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
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
class SeriesMoveTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repository = RoomSeriesRepository(database) { 100L }
    private val old = Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/old"), "Series")
    private val source = Listing("custom_mihon_2")
    private val new = Series(SeriesId(source.key, "/new"), "Series")

    @After fun close() = database.close()

    private suspend fun readOld(mode: GalleryReadingMode = GalleryReadingMode.entries.last()) {
        repository.store(old, (3 downTo 1).map { SeriesEntry("/o$it", "Chapter $it", it.toFloat()) })
        repository.setFollowed(old.id, true)
        repository.setReadingMode(old.id, mode)
        repository.setDownloadNewChapters(old.id, true)
        repository.recordProgress(old.id, "/o1", 5, 5)
        repository.recordProgress(old.id, "/o2", 5, 5)
        repository.recordProgress(old.id, "/o3", 4, 10)
    }

    @Test
    fun `a switch carries follow, mode and progress, removes the old series, then calls the hook`() = runTest {
        readOld()
        source.chapters = (4 downTo 1).map { SeriesEntry("/n$it", "Ch. $it", it.toFloat()) }
        var removed: SeriesId? = null

        val switched = repository.switchSource(old.id, source, new) { id ->
            assertNull("The hook runs once the move is stored", repository.series(old.id))
            removed = id
        }

        assertTrue(switched)
        assertEquals(old.id, removed)
        val moved = repository.series(new.id)!!
        assertTrue(moved.followed)
        assertEquals(GalleryReadingMode.entries.last(), moved.readingMode)
        assertTrue(moved.downloadNewChapters)
        assertEquals("/n3", moved.lastOpenedEntry)
        val entries = repository.entries(new.id).associateBy { it.entry.address }
        assertEquals(listOf("/n1", "/n2"), entries.values.filter { it.read }.map { it.entry.address }.sorted())
        assertEquals(4, entries.getValue("/n3").position)
        assertEquals(emptyList<Any>(), repository.entries(old.id))
        assertEquals(listOf(new.id), repository.observeFollowed().first().map { it.series.id })
    }

    @Test
    fun `chapters there before the switch are not announced, and later ones are`() = runTest {
        readOld()
        source.chapters = listOf(SeriesEntry("/n2", "Ch. 2", 2f), SeriesEntry("/n1", "Ch. 1", 1f))
        repository.switchSource(old.id, source, new)

        assertEquals(emptyList<SeriesUpdate>(), repository.checkFollowed(sourcesOn(source)).updates)
        val later = SeriesEntry("/n3", "Ch. 3", 3f)
        source.chapters = listOf(later) + source.chapters
        assertEquals(listOf(later), repository.checkFollowed(sourcesOn(source)).updates.single().newEntries)
    }

    @Test
    fun `a series already stored keeps what it had, and the hook failing undoes nothing`() = runTest {
        readOld()
        source.chapters = listOf(SeriesEntry("/n4", "Ch. 4", 4f), SeriesEntry("/n3", "Ch. 3", 3f), SeriesEntry("/n1", "Ch. 1", 1f))
        repository.store(new, source.chapters)
        repository.recordProgress(new.id, "/n3", 8, 10)
        repository.recordProgress(new.id, "/n4", 2, 10)

        repository.switchSource(old.id, source, new) { error("disk full") }

        val moved = repository.series(new.id)!!
        assertEquals("Its own further progress stays", "/n4", moved.lastOpenedEntry)
        val entries = repository.entries(new.id).associateBy { it.entry.address }
        assertTrue(entries.getValue("/n1").read)
        assertEquals(8, entries.getValue("/n3").position)
        assertNull(repository.series(old.id))
    }

    @Test
    fun `a failed fetch, a series without chapters, or a switch to the same series changes nothing`() = runTest {
        readOld()
        source.fails = true
        runCatching { repository.switchSource(old.id, source, new) }
        source.fails = false
        assertTrue(runCatching { repository.switchSource(old.id, source, new) }.exceptionOrNull() is NoChaptersToMoveToException)
        assertFalse(repository.switchSource(old.id, source, old))

        assertTrue(repository.series(old.id)!!.followed)
        assertNull(repository.series(new.id))
    }

    @Test
    fun `progress on chapters the old source no longer lists also refuses a series without chapters (ADR 0015, 0028)`() = runTest {
        readOld()
        repository.store(old, listOf(SeriesEntry("/o4", "Chapter 4", 4f)))

        assertTrue(runCatching { repository.switchSource(old.id, source, new) }.exceptionOrNull() is NoChaptersToMoveToException)
        assertTrue(repository.series(old.id)!!.followed)

        val partway = Series(SeriesId(old.id.source, "/partway"), "Series")
        repository.store(partway, listOf(SeriesEntry("/p1", "Chapter 1", 1f)))
        repository.recordProgress(partway.id, "/p1", 2, 10)
        repository.store(partway, listOf(SeriesEntry("/p2", "Chapter 2", 2f)))
        assertTrue(runCatching { repository.switchSource(partway.id, source, new) }.exceptionOrNull() is NoChaptersToMoveToException)
    }

    @Test
    fun `of two switches of one series, the second moves nothing and leaves the downloads to the first`() = runTest {
        readOld()
        source.chapters = listOf(SeriesEntry("/n1", "Ch. 1", 1f))
        val other = Series(SeriesId(source.key, "/other"), "Series")
        repository.switchSource(old.id, source, new)
        var removed = false

        assertFalse(repository.switchSource(old.id, source, other) { removed = true })

        assertFalse(removed)
        assertNull(repository.series(other.id))
    }
}

private class Listing(id: String) : SeriesSource {
    var chapters = emptyList<SeriesEntry>()
    var fails = false
    override val key = SourceKey.parseOrThrow(id)
    override val name = id
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = popular(page)
    override suspend fun details(series: Series): Series = if (fails) throw IllegalStateException("down") else series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(chapters)
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
}
