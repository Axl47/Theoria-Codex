package com.theoriacodex.app.series

import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesPageViewModelTest {
    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    /** As after restoring a backup on a device without the series' extension: the page shows "Extension unavailable". */
    @Test
    fun aStoredSeriesWhoseExtensionIsNotInstalledShowsItsChaptersAndSaysSo() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"), followed = true)
        val entries = listOf(StoredSeriesEntry(SeriesEntry("/chapter/1", "Ch.1"), sourceOrder = 0, firstSeenAtEpochMs = 1L, read = true, lastPage = 18))
        val viewModel = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() })
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.sourceMissing)
        assertFalse(state.refreshing)
        assertEquals(stored, state.stored)
        assertEquals(entries, state.entries)
        collecting.cancel()
    }

    /** A refresh finishing after a move to another source would store the old series again. */
    @Test
    fun noRefreshStartsWhileTheSeriesMovesOrOnceItMoved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        var asked = 0
        val viewModel = SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), loadSources = { asked++; emptyList() })
        advanceUntilIdle()

        viewModel.whileMoving { viewModel.refresh(); advanceUntilIdle(); false }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("Refreshes resume when nothing moved", 2, asked)

        viewModel.whileMoving { true }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, asked)
    }
}

/** Holds what a restore stored and never hears from a source. */
private class Restored(private val stored: StoredSeries, private val entries: List<StoredSeriesEntry>) : SeriesRepository {
    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = flowOf(stored)
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = flowOf(entries)
    override suspend fun series(id: SeriesId): StoredSeries? = stored
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = entries
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
