package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.model.SortDirection
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
        fun order(sort: LibrarySort) = library.arranged(LibraryOptions(sort = sort.choice()), "").map { it.series.title }
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
        assertEquals(listOf("Mid"), library.arranged(LibraryOptions(show = LibraryShow.DOWNLOADED), "", downloads).map { it.series.title })
    }

    @Test
    fun pickingTheActiveSortReversesItAndAnotherSortStartsInItsNaturalDirection() {
        val reversed = LibraryOptions().picking(LibrarySort.UNREAD)
        assertEquals(SortChoice(LibrarySort.UNREAD, SortDirection.ASCENDING), reversed.sort)
        assertEquals(listOf("Mid", "alpha", "Zeta"), library.arranged(reversed, "").map { it.series.title })
        assertEquals(SortChoice(LibrarySort.TITLE, SortDirection.ASCENDING), reversed.picking(LibrarySort.TITLE).sort)
        assertEquals(LibraryOptions(), reversed.reversed())
        val oldestReadFirst = LibraryOptions(sort = SortChoice(LibrarySort.RECENTLY_READ, SortDirection.ASCENDING))
        assertEquals(listOf("Mid", "Zeta", "alpha"), library.arranged(oldestReadFirst, "").map { it.series.title })
    }

    @Test
    fun cardsShowTheDownloadedSegmentOnlyUnderTheDownloadedFilter() {
        val mid = library.last()
        assertEquals(CardCounts(unread = 0, downloaded = 0), mid.cardCounts(LibraryShow.ALL, downloads))
        assertEquals(CardCounts(unread = 0, downloaded = 0), mid.cardCounts(LibraryShow.COMPLETED, downloads))
        assertEquals(CardCounts(unread = 0, downloaded = 5), mid.cardCounts(LibraryShow.DOWNLOADED, downloads))
        assertEquals(CardCounts(unread = 4, downloaded = 0), library.first().cardCounts(LibraryShow.DOWNLOADED, downloads))
    }

    private val downloads get() = mapOf(library.last().series.id to 5)

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
