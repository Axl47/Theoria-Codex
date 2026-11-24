package com.theoriacodex.app.series

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTabStaleAnswerTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun aSlowFirstPageOvertakenByANewerQueryNeverShows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering()
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) { NOTHING_CHECKED }
        advanceUntilIdle()
        val slowSearch = source.hold("one")
        val slowDedicated = source.hold("three")

        viewModel.search("one")
        advanceUntilIdle()
        viewModel.search("two")
        advanceUntilIdle()
        slowSearch.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("two"), (viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded).series.map { it.title })

        viewModel.openDedicatedSearch(source.key)
        viewModel.searchDedicated("three")
        advanceUntilIdle()
        viewModel.searchDedicated("four")
        advanceUntilIdle()
        slowDedicated.complete(Unit)
        advanceUntilIdle()
        val dedicated = viewModel.state.value.dedicated!!
        assertEquals(BrowseKind.Filtered("four", emptyList(), changed = 0), dedicated.kind)
        assertEquals(listOf("four"), (dedicated.source.listing as SeriesListing.Loaded).series.map { it.title })
    }

    @Test
    fun aPageStillLoadingWhenTheListingIsRefreshedNeverLands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) { NOTHING_CHECKED }
        advanceUntilIdle()
        val more = CompletableDeferred<Unit>()
        source.laterPagesWait = more
        viewModel.browseMore()
        advanceUntilIdle()

        viewModel.retryBrowse()
        advanceUntilIdle()
        more.complete(Unit)
        advanceUntilIdle()
        val refreshed = viewModel.state.value.browse?.listing as SeriesListing.Loaded
        assertEquals(SeriesListing.Loaded(refreshed.series, nextPage = 2), refreshed)
        assertEquals(listOf("popular"), browsed(viewModel))

        viewModel.browseMore()
        advanceUntilIdle()
        assertEquals(listOf("popular", "popular"), browsed(viewModel))
    }

    @Test
    fun leavingBrowseMidLoadKeepsTheLoadAndAsksTheSourceOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering()
        val slow = source.hold("popular")
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) { NOTHING_CHECKED }
        advanceUntilIdle()
        assertEquals(SeriesListing.Loading, viewModel.state.value.browse?.listing)

        viewModel.chooseView(SeriesView.FOLLOWING)
        viewModel.search("one punch")
        advanceUntilIdle()
        slow.complete(Unit)
        viewModel.clearSearch()
        viewModel.chooseView(SeriesView.BROWSE)
        advanceUntilIdle()

        assertEquals(listOf("popular"), browsed(viewModel))
        assertEquals(listOf("popular"), source.asked.filter { it == "popular" })
    }
}
