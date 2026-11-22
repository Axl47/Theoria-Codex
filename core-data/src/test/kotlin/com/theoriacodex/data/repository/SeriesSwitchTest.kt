package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSwitchTest {
    private val from = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/a"), "Series"))
    private val to = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_2"), "/b"), "Series"))

    @Test
    fun `a number matches the same translator group, else the first in reading order`() {
        val first = SeriesEntry("/1-x", "Ch. 1", 1f, "X")
        val ours = SeriesEntry("/1-a", "Ch. 1", 1f, "Group A")
        val candidates = listOf(first, ours, SeriesEntry("/2", "Ch. 2", 2f))

        assertEquals(ours, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f, "group-a"), candidates))
        assertEquals(first, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f, "Other"), candidates))
        assertEquals(first, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f), candidates))
    }

    @Test
    fun `without a number on either side, entries match by name, and nothing else does`() {
        val extra = SeriesEntry("/extra", "Extra: Side Story!")
        val numbered = SeriesEntry("/5", "Oneshot", 5f)
        val candidates = listOf(numbered, extra)

        assertEquals(extra, matchingEntry(SeriesEntry("/old", "extra side story"), candidates))
        assertEquals(numbered, matchingEntry(SeriesEntry("/old", "oneshot"), candidates))
        assertNull("A different number is another chapter, whatever its name", matchingEntry(SeriesEntry("/old", "Oneshot", 4f), candidates))
        assertNull(matchingEntry(SeriesEntry("/old", "Chapter 9", 9f), candidates))
    }

    @Test
    fun `entries up to the highest number read are read, and the open chapter resumes at its page`() {
        val old = listOf(
            stored(SeriesEntry("/o3", "3", 3f, "A"), order = 0, lastPage = 12),
            stored(SeriesEntry("/o2", "2", 2f, "A"), order = 1, read = true),
            stored(SeriesEntry("/o1", "1", 1f, "A"), order = 2),
        )
        val new = listOf(
            stored(SeriesEntry("/n4", "4", 4f), order = 0),
            stored(SeriesEntry("/n3-b", "3", 3f, "B"), order = 1),
            stored(SeriesEntry("/n3-a", "3", 3f, "A"), order = 2, pageCount = 8),
            stored(SeriesEntry("/n2.5", "2.5", 2.5f), order = 3),
            stored(SeriesEntry("/n2", "2", 2f), order = 4),
            stored(SeriesEntry("/n1", "1", 1f), order = 5),
        )

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o3"), old, to, new)

        assertEquals(listOf("/n1", "/n2", "/n3-a"), changed.map { it.entry.address }.sorted())
        assertTrue(changed.filter { it.entry.number!! <= 2f }.all { it.read })
        val resumed = changed.single { it.entry.address == "/n3-a" }
        assertEquals("Capped at the new chapter's page count", 8, resumed.lastPage)
        assertEquals(false, resumed.read)
        assertEquals("/n3-a", series.lastOpenedEntry)
    }

    @Test
    fun `with nothing to match, only the settings carry`() {
        val old = listOf(stored(SeriesEntry("/o1", "Prologue"), order = 0, read = true, lastPage = 4))
        val new = listOf(stored(SeriesEntry("/n1", "Chapter 1", 1f), order = 0))
        val chosen = from.copy(followed = true, readingMode = GalleryReadingMode.entries.last(), lastOpenedEntry = "/o1")

        val (series, changed) = carryProgress(chosen, old, to, new)

        assertEquals(emptyList<StoredSeriesEntry>(), changed)
        assertEquals(to.copy(followed = true, readingMode = chosen.readingMode), series)
    }

    @Test
    fun `a series already read further keeps its own progress`() {
        val old = listOf(stored(SeriesEntry("/o1", "1", 1f), order = 0, lastPage = 3))
        val new = listOf(
            stored(SeriesEntry("/n2", "2", 2f), order = 0, read = true, lastPage = 9, pageCount = 9),
            stored(SeriesEntry("/n1", "1", 1f), order = 1, lastPage = 5, pageCount = 9),
        )

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o1"), old, to.copy(followed = true, lastOpenedEntry = "/n2"), new)

        assertEquals(emptyList<StoredSeriesEntry>(), changed)
        assertEquals("/n2", series.lastOpenedEntry)
        assertTrue(series.followed)
    }

    private fun stored(entry: SeriesEntry, order: Int, read: Boolean = false, lastPage: Int = 0, pageCount: Int? = null) =
        StoredSeriesEntry(entry, order, firstSeenAtEpochMs = 0, read = read, lastPage = lastPage, pageCount = pageCount)
}
