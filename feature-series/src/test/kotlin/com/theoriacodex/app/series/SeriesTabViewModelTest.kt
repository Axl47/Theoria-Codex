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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

        sources.complete(listOf(Answering))
        advanceUntilIdle()
        val listing = viewModel.state.value.search?.single()?.listing as SeriesListing.Loaded
        assertEquals(listOf("one punch"), listing.series.map { it.title })
    }
}

/** Answers a search with one series titled by the text searched for. */
private object Answering : SeriesSource {
    override val key = SourceKey.parseOrThrow("custom_mihon_7")
    override val name = "Fake"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int) = SeriesPage(listOf(Series(SeriesId(key, "/found"), text)), hasNextPage = false)
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
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
}
