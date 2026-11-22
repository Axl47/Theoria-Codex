package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTabViewModelTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun aSearchSentWhileExtensionsLoadRunsOnceTheyHave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sources = CompletableDeferred<List<SeriesSource>>()
        val viewModel = SeriesTabViewModel(NothingStored, { sources.await() }) {}

        viewModel.search("one punch")
        advanceUntilIdle()
        assertEquals(emptyList<SeriesSourceListing>(), viewModel.state.value.search)

        sources.complete(listOf(Answering()))
        advanceUntilIdle()
        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("one punch"), listing.series.map { it.title })
    }

    @Test
    fun aSearchOnScreenRunsInASourceTurnedOnMeanwhile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var offered = listOf<SeriesSource>(Answering())
        val changed = MutableStateFlow(0)
        val viewModel = SeriesTabViewModel(NothingStored, { offered }, changed) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()

        offered = offered + Answering("custom_mihon_8")
        changed.value = 1
        advanceUntilIdle()

        val found = viewModel.state.value.search.orEmpty().map { (it.listing as SeriesListing.Loaded).series.single().id.source.id }
        assertEquals(listOf("custom_mihon_7", "custom_mihon_8"), found)
    }

    @Test
    fun theSearchAndBrowsedSourceShowAgainAfterProcessDeath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val offered = listOf<SeriesSource>(Answering(), Answering("custom_mihon_8"))
        val saved = SavedStateHandle()
        val before = SeriesTabViewModel(NothingStored, { offered }, saved = saved) {}
        advanceUntilIdle()
        before.browse(offered[1].key)
        before.search("one punch")
        advanceUntilIdle()

        // The system keeps only the handle's contents; the sources load again in the new process.
        val after = SeriesTabViewModel(NothingStored, { offered }, saved = SavedStateHandle(saved.keys().associateWith { saved.get<String>(it) })) {}
        assertEquals(emptyList<SeriesSourceListing>(), after.state.value.search)
        advanceUntilIdle()

        assertEquals(offered[1].key, after.state.value.browse?.key)
        val found = after.state.value.search.orEmpty().map { (it.listing as SeriesListing.Loaded).series.single().title }
        assertEquals(listOf("one punch", "one punch"), found)
    }

    @Test
    fun moreAddsASourcesNextPageOfResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()

        viewModel.searchMore(source.key)
        advanceUntilIdle()

        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("/found", "/found2"), listing.series.map { it.id.address })
        assertEquals(null, listing.nextPage)
    }

    @Test
    fun theSameSearchSubmittedAgainDropsAPageStillLoadingForTheFirst() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Answering(pages = 2)
        val viewModel = SeriesTabViewModel(NothingStored, { listOf(source) }) {}
        advanceUntilIdle()
        viewModel.search("one punch")
        advanceUntilIdle()
        val more = CompletableDeferred<Unit>()
        source.laterPagesWait = more
        viewModel.searchMore(source.key)
        advanceUntilIdle()

        viewModel.search("one punch")
        advanceUntilIdle()
        more.complete(Unit)
        advanceUntilIdle()

        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("/found"), listing.series.map { it.id.address })
        assertEquals(2, listing.nextPage)
    }
}

/** Answers a search with one series a page, titled by the text searched for, over [pages] pages. */
private class Answering(id: String = "custom_mihon_7", private val pages: Int = 1) : SeriesSource {
    override val key = SourceKey.parseOrThrow(id)
    override val name = "Fake"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)

    /** What a page after the first waits for, when set. */
    var laterPagesWait: CompletableDeferred<Unit>? = null
    override suspend fun search(text: String, page: Int): SeriesPage {
        if (page > 1) laterPagesWait?.await()
        return SeriesPage(listOf(Series(SeriesId(key, if (page == 1) "/found" else "/found$page"), text)), hasNextPage = page < pages)
    }
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series) = emptyList<SeriesEntry>()
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()
}

private object NothingStored : SeriesRepository {
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = emptyFlow()
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = emptyFlow()
    override suspend fun series(id: SeriesId): StoredSeries? = null
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = emptyList()
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
