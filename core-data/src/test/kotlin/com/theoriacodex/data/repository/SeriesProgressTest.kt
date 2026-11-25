package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesProgressTest {
    @Test
    fun `a chapter is read once its last page was reached, and stays read`() {
        val midway = entry("c1", order = 0).withProgress(position = 3, length = 5, SeriesKind.MANGA)
        assertFalse(midway.read)
        assertEquals(3L, midway.position)
        assertEquals(2, midway.resumePageIndex())

        val finished = midway.withProgress(position = 5, length = 5, SeriesKind.MANGA)
        assertTrue(finished.read)

        val reread = finished.withProgress(position = 2, length = 5, SeriesKind.MANGA)
        assertTrue(reread.read)
        assertEquals(2L, reread.position)
        assertEquals("A finished entry opens at its start", 0, reread.resumePageIndex())
    }

    @Test
    fun `a page past the end counts as the last page`() {
        assertEquals(4L, entry("c1", order = 0).withProgress(position = 9, length = 4, SeriesKind.MANGA).position)
    }

    @Test
    fun `an offset comes back only with the page it was measured on (ADR 0192)`() {
        val inside = entry("c1", order = 0).withProgress(position = 3, length = 9, SeriesKind.MANGA, pageOffset = 0.4f)
        assertEquals(0.4f, inside.resumePageOffset())

        val moved = inside.copy(position = 5)
        assertNull("Moving the page by hand leaves no stale offset", moved.pageOffset)
        assertNull("A page moved by hand does not revive its old offset", moved.withProgress(position = 3, length = 9, SeriesKind.MANGA).pageOffset)
        assertNull(moved.copy(read = false, position = 0).pageOffset)
        assertEquals("An unmeasurable offset keeps the stored one", 0.4f, inside.withProgress(position = 3, length = 9, SeriesKind.MANGA, pageOffset = Float.NaN).pageOffset)

        val back = inside.withProgress(position = 4, length = 9, SeriesKind.MANGA).withProgress(position = 3, length = 9, SeriesKind.MANGA)
        assertNull("Returning to a page does not revive its old offset", back.pageOffset)
        assertNull(inside.copy(read = true).resumePageOffset())
    }

    @Test
    fun `an episode is watched from 85 percent of its length, stays watched, and resumes where it was left short of its last 2 minutes (ADR 0028)`() {
        val length = 1_200_000L
        val midway = entry("e1", order = 0).withProgress(position = 1_019_999, length = length, SeriesKind.ANIME)
        assertFalse("Short of 85%", midway.read)
        assertEquals(1_019_999L, midway.resumePositionMs())

        val watched = midway.withProgress(position = 1_020_000, length = length, SeriesKind.ANIME)
        assertTrue(watched.read)
        assertEquals("Watched, but left in its credits", 1_020_000L, watched.resumePositionMs())

        val finished = watched.withProgress(position = length - 120_000, length = length, SeriesKind.ANIME)
        assertEquals("Within its last 2 minutes it starts over", 0L, finished.resumePositionMs())
        val clip = entry("e2", order = 1).withProgress(position = 54_000, length = 60_000, SeriesKind.ANIME)
        assertEquals("A short one's tail is its last 10%", 0L, clip.resumePositionMs())
        assertEquals(53_000L, clip.withProgress(position = 53_000, length = 60_000, SeriesKind.ANIME).resumePositionMs())
        val switched = entry("e3", order = 2).copy(read = true, position = 1_230_000)
        assertEquals("Without a duration, as after a switch, it resumes where it was left", 1_230_000L, switched.resumePositionMs())

        val rewatched = finished.withProgress(position = 60_000, length = length, SeriesKind.ANIME)
        assertTrue(rewatched.read)
        assertEquals("A rewatch left partway resumes", 60_000L, rewatched.resumePositionMs())
    }

    @Test
    fun `an episode left at its first moment is not read, and a position past its end counts as its end`() {
        val opened = entry("e1", order = 0).withProgress(position = 0, length = 1_000, SeriesKind.ANIME)
        assertFalse(opened.read)
        assertEquals(0L, opened.position)

        assertEquals(1_000L, entry("e1", order = 0).withProgress(position = 5_000, length = 1_000, SeriesKind.ANIME).position)
    }

    @Test
    fun `an episode of unknown length records nothing`() {
        val episode = entry("/1", order = 0)

        assertEquals(episode, episode.withProgress(position = 0, length = 0, kind = SeriesKind.ANIME))
    }

    @Test
    fun `continue resumes the entry opened last while it is unfinished (ADR 0028)`() {
        val entries = listOf(entry("c3", order = 0), entry("c2", order = 1, position = 4), entry("c1", order = 2))

        assertEquals("c2", continueEntry(series(lastOpened = "c2"), entries)?.entry?.address)
    }

    @Test
    fun `continue moves to the first unread entry in reading order once the last one is finished (ADR 0028)`() {
        val entries = listOf(entry("c3", order = 0), entry("c2", order = 1), entry("c1", order = 2, read = true))

        assertEquals("c2", continueEntry(series(lastOpened = "c1"), entries)?.entry?.address)
        assertEquals("c2", continueEntry(series(lastOpened = null), entries)?.entry?.address)
    }

    @Test
    fun `after a finished entry, continue takes the next while unread, else one partway through, else the first unread (ADR 0028)`() {
        val newestFinished = listOf(entry("c4", order = 0, read = true), entry("c3", order = 1, position = 5), entry("c2", order = 2, position = 1), entry("c1", order = 3))
        assertEquals("c3", continueEntry(series(lastOpened = "c4"), newestFinished)?.entry?.address)

        val nextRead = listOf(entry("c5", order = 0, position = 2), entry("c4", order = 1, position = 9), entry("c3", order = 2, read = true), entry("c2", order = 3, read = true), entry("c1", order = 4))
        assertEquals("c5", continueEntry(series(lastOpened = "c2"), nextRead)?.entry?.address)

        val nextUnread = listOf(entry("c3", order = 0, position = 7), entry("c2", order = 1), entry("c1", order = 2, read = true))
        assertEquals("c2", continueEntry(series(lastOpened = "c1"), nextUnread)?.entry?.address)

        val gaps = listOf(entry("c4", order = 0, read = true), entry("c3", order = 1), entry("c2", order = 2), entry("c1", order = 3, read = true))
        assertEquals("c2", continueEntry(series(lastOpened = "c4"), gaps)?.entry?.address)
    }

    @Test
    fun `continue returns to the entry opened last when everything is read, and nothing without entries (ADR 0028)`() {
        val entries = listOf(entry("c2", order = 0, read = true), entry("c1", order = 1, read = true))

        assertEquals("c1", continueEntry(series(lastOpened = "c1"), entries)?.entry?.address)
        assertNull(continueEntry(series(lastOpened = "c1"), emptyList()))
    }

    @Test
    fun `with its opened chapter no longer listed, continue takes the latest one partway through, else the first unread, else the last (ADR 0014)`() {
        val unlisted = series(lastOpened = "gone")

        val partway = listOf(entry("c3", order = 0), entry("c2", order = 1, position = 3), entry("c1", order = 2, position = 2))
        assertEquals("c2", continueEntry(unlisted, partway)?.entry?.address)
        val unread = listOf(entry("c3", order = 0), entry("c2", order = 1), entry("c1", order = 2, read = true))
        assertEquals("c2", continueEntry(unlisted, unread)?.entry?.address)
        val read = listOf(entry("c2", order = 0, read = true), entry("c1", order = 1, read = true))
        assertEquals("c2", continueEntry(unlisted, read)?.entry?.address)
        val readWithPage = listOf(entry("c3", order = 0), entry("c2", order = 1, read = true, position = 9), entry("c1", order = 2, read = true))
        assertEquals("c3", continueEntry(unlisted, readWithPage)?.entry?.address)
    }

    private fun series(lastOpened: String?) =
        StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/s"), "Series"), lastOpenedEntry = lastOpened)

    private fun entry(address: String, order: Int, read: Boolean = false, position: Long = 0) =
        StoredSeriesEntry(SeriesEntry(address, address), sourceOrder = order, firstSeenAtEpochMs = 1L, read = read, position = position)
}
