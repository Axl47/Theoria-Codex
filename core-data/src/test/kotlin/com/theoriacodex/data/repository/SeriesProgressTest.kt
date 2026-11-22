package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesProgressTest {
    @Test
    fun `an entry is read once its last page was reached, and stays read`() {
        val midway = entry("c1", order = 0).withProgress(page = 3, pageCount = 5)
        assertFalse(midway.read)
        assertEquals(3, midway.lastPage)
        assertEquals(2, midway.resumePageIndex())

        val finished = midway.withProgress(page = 5, pageCount = 5)
        assertTrue(finished.read)

        val reread = finished.withProgress(page = 2, pageCount = 5)
        assertTrue(reread.read)
        assertEquals(2, reread.lastPage)
        assertEquals("A finished entry opens at its start", 0, reread.resumePageIndex())
    }

    @Test
    fun `a page past the end counts as the last page`() {
        assertEquals(4, entry("c1", order = 0).withProgress(page = 9, pageCount = 4).lastPage)
    }

    @Test
    fun `continue resumes the entry opened last while it is unfinished`() {
        val entries = listOf(entry("c3", order = 0), entry("c2", order = 1, lastPage = 4), entry("c1", order = 2))

        assertEquals("c2", continueEntry(series(lastOpened = "c2"), entries)?.entry?.address)
    }

    @Test
    fun `continue moves to the first unread entry in reading order once the last one is finished`() {
        val entries = listOf(entry("c3", order = 0), entry("c2", order = 1), entry("c1", order = 2, read = true))

        assertEquals("c2", continueEntry(series(lastOpened = "c1"), entries)?.entry?.address)
        assertEquals("c2", continueEntry(series(lastOpened = null), entries)?.entry?.address)
    }

    @Test
    fun `continue returns to the entry opened last when everything is read, and nothing without entries`() {
        val entries = listOf(entry("c2", order = 0, read = true), entry("c1", order = 1, read = true))

        assertEquals("c1", continueEntry(series(lastOpened = "c1"), entries)?.entry?.address)
        assertNull(continueEntry(series(lastOpened = "c1"), emptyList()))
    }

    private fun series(lastOpened: String?) =
        StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_1"), "/s"), "Series"), lastOpenedEntry = lastOpened)

    private fun entry(address: String, order: Int, read: Boolean = false, lastPage: Int = 0) =
        StoredSeriesEntry(SeriesEntry(address, address), sourceOrder = order, firstSeenAtEpochMs = 1L, read = read, lastPage = lastPage)
}
