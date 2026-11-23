package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SeriesSwitchTest {
    private val from = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/a"), "Series"))
    private val to = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_2"), "/b"), "Series"))

    @Test
    fun `progress does not move between a manga and an anime`() {
        val anime = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_2"), "/b"), "Series"))

        assertThrows(IllegalArgumentException::class.java) { carryProgress(from, emptyList(), anime, emptyList()) }
    }

    @Test
    fun `a number matches the same translator group, else the first in reading order`() {
        val first = SeriesEntry("/1-x", "Ch. 1", 1f, "X")
        val ours = SeriesEntry("/1-a", "Ch. 1", 1f, "Group A")
        val candidates = listOf(first, ours, SeriesEntry("/2", "Ch. 2", 2f))

        assertEquals(ours, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f, "group-a"), emptyList(), candidates))
        assertEquals(first, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f, "Other"), emptyList(), candidates))
        assertEquals(first, matchingEntry(SeriesEntry("/old", "Chapter 1", 1f), emptyList(), candidates))
    }

    @Test
    fun `a name matches where either lacks a number, when no other entry on either side has it`() {
        val extra = SeriesEntry("/extra", "Extra: Side Story!")
        val numbered = SeriesEntry("/5", "Oneshot", 5f)
        val half = SeriesEntry("/10.5", "Chapter 10.5")
        val candidates = listOf(numbered, extra, half, SeriesEntry("/n1", "Notice"), SeriesEntry("/n2", "notice"))
        fun match(entry: SeriesEntry, own: List<SeriesEntry> = listOf(entry)) = matchingEntry(entry, own, candidates)

        assertEquals(extra, match(SeriesEntry("/old", "extra side story")))
        assertEquals(numbered, match(SeriesEntry("/old", "oneshot")))
        assertEquals(half, match(SeriesEntry("/old", "Chapter 10.5")))
        assertNull("The point is kept", match(SeriesEntry("/old", "Chapter 105")))
        assertNull("A different number is another chapter, whatever its name", match(SeriesEntry("/old", "Oneshot", 4f)))
        assertNull("Two candidates have the name", match(SeriesEntry("/old", "Notice")))
        val twice = SeriesEntry("/old", "Extra: Side Story")
        assertNull("Two of its own entries have the name", match(twice, listOf(twice, twice.copy(address = "/old2"))))
    }

    @Test
    fun `the chapter the user is in bounds what is read, and a chapter read beyond it is read alone`() {
        val old = listOf(
            stored(SeriesEntry("/o200", "200", 200f), order = 0, read = true),
            stored(SeriesEntry("/o12", "12", 12f, "A"), order = 1, position = 12),
            stored(SeriesEntry("/o1", "1", 1f), order = 2),
        )
        val new = listOf(200f, 13f, 12f, 12f, 11f, 1f).mapIndexed { order, number ->
            val group = if (order == 2) "B" else if (order == 3) "A" else null
            stored(SeriesEntry("/n${number.toInt()}${group.orEmpty()}", "$number", number, group), order = order, length = if (group == "A") 8 else null)
        }

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o12"), old, to, new)

        val byAddress = changed.associateBy { it.entry.address }
        assertEquals(setOf("/n1", "/n11", "/n200"), changed.filter { it.read }.map { it.entry.address }.toSet())
        val resumed = byAddress.getValue("/n12A")
        assertEquals("Unfinished, capped at the new chapter's page count", 8L to false, resumed.position to resumed.read)
        assertEquals("/n12A", series.lastOpenedEntry)
    }

    @Test
    fun `a finished chapter stays finished, and without one opened only chapters read there are read`() {
        val old = listOf(stored(SeriesEntry("/o3", "3", 3f), order = 0, read = true, position = 9), stored(SeriesEntry("/o1", "1", 1f), order = 1))
        val new = listOf(3f, 2f, 1f).mapIndexed { order, number -> stored(SeriesEntry("/n$number", "$number", number), order) }

        val (_, finished) = carryProgress(from.copy(lastOpenedEntry = "/o3"), old, to, new)
        assertEquals(setOf("/n1.0", "/n2.0", "/n3.0"), finished.filter { it.read }.map { it.entry.address }.toSet())

        val (series, alone) = carryProgress(from, old, to, new)
        assertEquals(listOf("/n3.0"), alone.map { it.entry.address })
        assertEquals("The furthest chapter read is where Continue goes on from", "/n3.0", series.lastOpenedEntry)
    }

    @Test
    fun `with nothing to match, only the settings carry`() {
        val old = listOf(stored(SeriesEntry("/o1", "Prologue"), order = 0, read = true, position = 4))
        val new = listOf(stored(SeriesEntry("/n1", "Chapter 1", 1f), order = 0))
        val chosen = from.copy(followed = true, readingMode = GalleryReadingMode.entries.last(), lastOpenedEntry = "/o1")

        val (series, changed) = carryProgress(chosen, old, to, new)

        assertEquals(emptyList<StoredSeriesEntry>(), changed)
        assertEquals(to.copy(followed = true, readingMode = chosen.readingMode), series)
    }

    @Test
    fun `a series already read further keeps its own progress`() {
        val old = listOf(stored(SeriesEntry("/o1", "1", 1f), order = 0, position = 3))
        val new = listOf(
            stored(SeriesEntry("/n2", "2", 2f), order = 0, read = true, position = 9, length = 9),
            stored(SeriesEntry("/n1", "1", 1f), order = 1, position = 5, length = 9),
        )

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o1"), old, to.copy(followed = true, lastOpenedEntry = "/n2"), new)

        assertEquals(emptyList<StoredSeriesEntry>(), changed)
        assertEquals(to.copy(followed = true, lastOpenedEntry = "/n2"), series)
    }

    private fun stored(entry: SeriesEntry, order: Int, read: Boolean = false, position: Long = 0, length: Long? = null) =
        StoredSeriesEntry(entry, order, firstSeenAtEpochMs = 0, read = read, position = position, length = length)
}
