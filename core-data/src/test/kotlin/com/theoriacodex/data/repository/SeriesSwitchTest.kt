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
    fun `a switch carries the per-series settings and the earlier followed time, never the shared-first-page flag (ADR 0028)`() {
        val old = from.copy(followed = true, readingMode = GalleryReadingMode.VERTICAL, lastOpenedEntry = "/1",
            downloadNewChapters = true, chaptersShareFirstPage = true, followedAtEpochMs = 5L, checkedAtEpochMs = 9L)
        val target = to.copy(followed = true, followedAtEpochMs = 7L)

        assertEquals(target.copy(readingMode = GalleryReadingMode.VERTICAL, downloadNewChapters = true, followedAtEpochMs = 5L),
            target.withSettingsOf(old))
        assertEquals(3L, target.copy(followedAtEpochMs = 3L).withSettingsOf(old).followedAtEpochMs)
    }

    @Test
    fun `progress does not move between a manga and an anime (ADR 0028)`() {
        val anime = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_2"), "/b"), "Series"))

        assertThrows(IllegalArgumentException::class.java) { carryProgress(from, emptyList(), anime, emptyList()) }
    }

    @Test
    fun `a number matches the same translator group, else the first in reading order (ADR 0028)`() {
        val first = SeriesEntry("/1-x", "Ch. 1", 1f, "X")
        val ours = SeriesEntry("/1-a", "Ch. 1", 1f, "Group A")
        val candidates = listOf(first, ours, SeriesEntry("/2", "Ch. 2", 2f))

        assertEquals(ours, EntryMatcher(emptyList(), candidates).match(SeriesEntry("/old", "Chapter 1", 1f, "group-a")))
        assertEquals(first, EntryMatcher(emptyList(), candidates).match(SeriesEntry("/old", "Chapter 1", 1f, "Other")))
        assertEquals(first, EntryMatcher(emptyList(), candidates).match(SeriesEntry("/old", "Chapter 1", 1f)))
    }

    @Test
    fun `a name matches where either lacks a number, when no other entry on either side has it (ADR 0028)`() {
        val extra = SeriesEntry("/extra", "Extra: Side Story!")
        val numbered = SeriesEntry("/5", "Oneshot", 5f)
        val half = SeriesEntry("/10.5", "Chapter 10.5")
        val candidates = listOf(numbered, extra, half, SeriesEntry("/n1", "Notice"), SeriesEntry("/n2", "notice"))
        fun match(entry: SeriesEntry, own: List<SeriesEntry> = listOf(entry)) = EntryMatcher(own, candidates).match(entry)

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
    fun `below the chapter the user is in, only chapters the old source lacks are filled in as read (ADR 0028)`() {
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
        assertEquals(setOf("/n11", "/n200"), changed.filter { it.read }.map { it.entry.address }.toSet())
        val resumed = byAddress.getValue("/n12A")
        assertEquals("Unfinished, capped at the new chapter's page count", 8L to false, resumed.position to resumed.read)
        assertEquals("/n12A", series.lastOpenedEntry)
    }

    @Test
    fun `a finished chapter stays finished, and without one opened only chapters read there are read (ADR 0028)`() {
        val old = listOf(stored(SeriesEntry("/o3", "3", 3f), order = 0, read = true, position = 9), stored(SeriesEntry("/o1", "1", 1f), order = 1))
        val new = listOf(3f, 2f, 1f).mapIndexed { order, number -> stored(SeriesEntry("/n$number", "$number", number), order) }

        val (_, finished) = carryProgress(from.copy(lastOpenedEntry = "/o3"), old, to, new)
        assertEquals(setOf("/n2.0", "/n3.0"), finished.filter { it.read }.map { it.entry.address }.toSet())

        val (series, alone) = carryProgress(from, old, to, new)
        assertEquals(listOf("/n3.0"), alone.map { it.entry.address })
        assertEquals("The furthest chapter read is where Continue goes on from", "/n3.0", series.lastOpenedEntry)
    }

    @Test
    fun `every chapter partway through keeps its place, the further one winning, capped at its page count (ADR 0028)`() {
        val old = listOf(
            stored(SeriesEntry("/o3", "3", 3f), order = 0, position = 4),
            stored(SeriesEntry("/o2", "2", 2f), order = 1, position = 15),
            stored(SeriesEntry("/o1", "1", 1f), order = -1, position = 6),
        )
        val new = listOf(
            stored(SeriesEntry("/n3", "3", 3f), order = 0),
            stored(SeriesEntry("/n2", "2", 2f), order = 1, length = 10),
            stored(SeriesEntry("/n1", "1", 1f), order = 2, position = 8),
        )

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o3"), old, to, new)

        assertEquals(listOf("/n2" to 10L, "/n3" to 4L), changed.map { it.entry.address to it.position })
        assertEquals("Nothing is read by a place alone", emptyList<String>(), changed.filter { it.read }.map { it.entry.address })
        assertEquals("/n3", series.lastOpenedEntry)
    }

    @Test
    fun `an episode keeps its own watched state and place, and one the old source lacks stays unwatched (ADR 0028)`() {
        val watching = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_1"), "/a"), "Series"), lastOpenedEntry = "/o5")
        val switched = StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_2"), "/b"), "Series"))
        val old = listOf(
            stored(SeriesEntry("/o5", "5", 5f), order = 0, position = 600_000, length = 1_440_000),
            stored(SeriesEntry("/o3", "3", 3f), order = 1, position = 300_000, length = 1_440_000),
            stored(SeriesEntry("/o2", "2", 2f), order = 2, read = true, position = 1_400_000, length = 1_440_000),
        )
        val new = listOf(5f, 4f, 3f, 2f, 1f).mapIndexed { order, number ->
            stored(SeriesEntry("/n${number.toInt()}", "$number", number), order = order, length = if (number == 2f) 1_380_000 else null)
        } + stored(SeriesEntry("/n2dub", "2", 2f, "Dub"), order = 5)

        val (series, changed) = carryProgress(watching, old, switched, new)

        assertEquals(
            listOf(Triple("/n2", true, 1_380_000L), Triple("/n3", false, 300_000L), Triple("/n5", false, 600_000L)),
            changed.map { Triple(it.entry.address, it.read, it.position) },
        )
        assertEquals("/n5", series.lastOpenedEntry)
    }

    @Test
    fun `each release of a chapter keeps its own read state, and another group's release takes the chapter's (ADR 0028)`() {
        val old = listOf(
            stored(SeriesEntry("/o5a", "5", 5f, "A"), order = 0, read = true),
            stored(SeriesEntry("/o5b", "5", 5f, "B"), order = 1, position = 4),
            stored(SeriesEntry("/o5d", "5", 5f, "D"), order = 2, read = true, position = 9),
        )
        val new = listOf(
            stored(SeriesEntry("/n5c", "5", 5f, "C"), order = 0),
            stored(SeriesEntry("/n5a", "5", 5f, "A"), order = 1),
            stored(SeriesEntry("/n5b", "5", 5f, "B"), order = 2),
        )

        val (_, changed) = carryProgress(from, old, to, new)

        assertEquals(
            listOf(Triple("/n5b", false, 4L), Triple("/n5a", true, 0L), Triple("/n5c", true, 0L)),
            changed.map { Triple(it.entry.address, it.read, it.position) },
        )
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
    fun `a series already read further keeps its own progress (ADR 0028)`() {
        val old = listOf(stored(SeriesEntry("/o1", "1", 1f), order = 0, position = 3))
        val new = listOf(
            stored(SeriesEntry("/n2", "2", 2f), order = 0, read = true, position = 9, length = 9),
            stored(SeriesEntry("/n1", "1", 1f), order = 1, position = 5, length = 9),
        )

        val (series, changed) = carryProgress(from.copy(lastOpenedEntry = "/o1"), old, to.copy(followed = true, lastOpenedEntry = "/n2"), new)

        assertEquals(emptyList<StoredSeriesEntry>(), changed)
        assertEquals(to.copy(followed = true, lastOpenedEntry = "/n2"), series)
    }

    @Test
    fun `each read time goes to the chapter it matches, also with no chapter opened last (ADR 0028)`() {
        val old = listOf(
            stored(SeriesEntry("/o2", "2", 2f), order = 0).copy(lastReadAtEpochMs = 20L),
            stored(SeriesEntry("/o1", "1", 1f), order = 1).copy(lastReadAtEpochMs = 10L),
        )
        val new = listOf(
            stored(SeriesEntry("/n2", "2", 2f), order = 0).copy(lastReadAtEpochMs = 30L),
            stored(SeriesEntry("/n1", "1", 1f), order = 1),
        )

        val (_, changed) = carryProgress(from, old, to, new)

        assertEquals(listOf("/n1" to 10L), changed.map { it.entry.address to it.lastReadAtEpochMs })
    }

    private fun stored(entry: SeriesEntry, order: Int, read: Boolean = false, position: Long = 0, length: Long? = null) =
        StoredSeriesEntry(entry, order, firstSeenAtEpochMs = 0, read = read, position = position, length = length)
}
