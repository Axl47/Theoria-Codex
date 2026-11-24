package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesLibraryTest {
    private val source = SourceKey.parseOrThrow("custom_mihon_1")

    private val library = listOf(
        followed("Zeta", unread = 4, started = true, updated = 10, read = 30, added = 1),
        followed("alpha", unread = 1, started = false, updated = 40, read = null, added = 3),
        followed("Mid", unread = 0, started = true, updated = null, read = 20, added = 2, status = SeriesStatus.COMPLETED),
    )

    @Test
    fun eachSortOrdersByItsTimeNewestFirstWithUnknownTimesLast() {
        fun order(sort: LibrarySort) = library.arranged(LibraryOptions(sort = sort), "").map { it.series.title }
        assertEquals(listOf("Zeta", "alpha", "Mid"), order(LibrarySort.UNREAD))
        assertEquals(listOf("alpha", "Zeta", "Mid"), order(LibrarySort.LAST_UPDATED))
        assertEquals(listOf("Zeta", "Mid", "alpha"), order(LibrarySort.RECENTLY_READ))
        assertEquals(listOf("alpha", "Mid", "Zeta"), order(LibrarySort.RECENTLY_ADDED))
        assertEquals(listOf("alpha", "Mid", "Zeta"), order(LibrarySort.TITLE))
    }

    @Test
    fun eachFilterAndTheTitleQueryNarrowTheList() {
        fun shown(show: LibraryShow, query: String = "") = library.arranged(LibraryOptions(show = show), query).map { it.series.title }
        assertEquals(listOf("Zeta", "alpha"), shown(LibraryShow.UNREAD))
        assertEquals(listOf("alpha"), shown(LibraryShow.NOT_STARTED))
        assertEquals(listOf("Zeta"), shown(LibraryShow.IN_PROGRESS))
        assertEquals(listOf("Mid"), shown(LibraryShow.CAUGHT_UP))
        assertEquals(listOf("Mid"), shown(LibraryShow.COMPLETED))
        assertEquals(listOf("alpha"), shown(LibraryShow.ALL, query = " ALP "))
    }

    private fun followed(title: String, unread: Int, started: Boolean, updated: Long?, read: Long?, added: Long, status: SeriesStatus = SeriesStatus.ONGOING) =
        FollowedSeries(
            series = Series(SeriesId(source, "/$title"), title, status = status),
            unreadCount = unread,
            followedAtEpochMs = added,
            lastReadAtEpochMs = read,
            lastUpdatedAtEpochMs = updated,
            started = started,
        )
}
