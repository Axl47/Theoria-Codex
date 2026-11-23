package com.theoriacodex.app.series

import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryWordingTest {
    @Test
    fun anEpisodeShowsTheTimeWatchedAsTheViewerWritesItOrThatItWasWatched() {
        assertEquals("8:12 / 23:40", entryDetail(episode(position = 492_000, length = 1_420_000), SeriesKind.ANIME))
        assertEquals("1:02:05 / 1:30:00", entryDetail(episode(position = 3_725_000, length = 5_400_000), SeriesKind.ANIME))
        assertEquals("Watched", entryDetail(episode(position = 1_400_000, length = 1_420_000, read = true), SeriesKind.ANIME))
    }

    @Test
    fun anAnimeCountsEpisodes() {
        val words = SeriesKind.ANIME.words
        assertEquals(listOf("1 episode", "12 episodes", "No episodes"), listOf(words.count(1), words.count(12), words.none))
    }

    private fun episode(position: Long, length: Long, read: Boolean = false) =
        StoredSeriesEntry(SeriesEntry("/e", "Episode 1"), sourceOrder = 0, firstSeenAtEpochMs = 0, read = read, position = position, length = length)
}
