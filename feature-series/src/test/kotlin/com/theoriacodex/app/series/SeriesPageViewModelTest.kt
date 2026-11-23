package com.theoriacodex.app.series

import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.UnsupportedEntriesException
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesPageViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    /** As after restoring a backup on a device without the series' extension: the page shows "Extension unavailable". */
    @Test
    fun aStoredSeriesWhoseExtensionIsNotInstalledShowsItsChaptersAndSaysSo() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"), followed = true)
        val entries = listOf(StoredSeriesEntry(SeriesEntry("/chapter/1", "Ch.1"), sourceOrder = 0, firstSeenAtEpochMs = 1L, read = true, position = 18))
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

    @Test
    fun anAnimeOffersNoDownloads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        fun page(source: String): SeriesPageViewModel {
            val id = SeriesId(SourceKey.parseOrThrow(source), "/title/1")
            return SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), { emptyList() }, downloads = downloads())
        }

        assertTrue(page("custom_mihon_7").canDownload)
        assertFalse(page("custom_aniyomi_7").canDownload)
    }

    /** As an Aniyomi source that groups an anime's episodes into seasons reports it. */
    @Test
    fun aSourceThatCannotListTheEntriesSaysWhyInItsOwnWords() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/title/1")
        val source = Seasons(id.source)
        val viewModel = SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), loadSources = { listOf(source) })
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        assertEquals(Seasons.MESSAGE, viewModel.state.value.failure)
        collecting.cancel()
    }

    /** Downloads over this test's folder that never run: the page only asks whether it offers them. */
    private fun downloads() = SeriesDownloads(
        store = OfflineMediaStore(folder.newFolder()),
        queueFile = folder.root.resolve("queue.json"),
        repository = Restored(StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/"), "")), emptyList()),
        sources = { emptyList() },
        fetch = { _, _ -> error("Nothing downloads") },
        scheduler = object : DownloadScheduler {
            override suspend fun schedule(automatic: Boolean) = Unit
            override suspend fun ensureScheduled(automatic: Boolean) = Unit
        },
        deleteAfterReading = { false },
    )
}

/** Fails every entry list the way a source that groups episodes into seasons does. */
private class Seasons(override val key: SourceKey) : SeriesSource {
    override val name = "Fake"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), hasNextPage = false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = popular(page)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series): List<SeriesEntry> = throw UnsupportedEntriesException(MESSAGE)
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Streams(emptyList())

    companion object {
        const val MESSAGE = "Anime split into seasons aren't supported yet"
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
    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}
