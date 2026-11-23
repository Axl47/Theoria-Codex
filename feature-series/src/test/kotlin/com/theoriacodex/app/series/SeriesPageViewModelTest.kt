package com.theoriacodex.app.series

import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test
    fun chapterSortingFilteringAndSearchUpdateVisibleEntriesWithoutChangingStoredEntries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"))
        val entries = listOf(
            StoredSeriesEntry(SeriesEntry("/c/extra", "Bonus", number = null, translatorGroup = "Alpha", uploadedAtEpochMs = 300L), sourceOrder = 0, firstSeenAtEpochMs = 1L),
            StoredSeriesEntry(SeriesEntry("/c/10", "Chapter 10", number = 10f, translatorGroup = "Beta", uploadedAtEpochMs = 100L), sourceOrder = 1, firstSeenAtEpochMs = 1L, read = true),
            StoredSeriesEntry(SeriesEntry("/c/2", "Chapter 2", number = 2f, translatorGroup = "Alpha", uploadedAtEpochMs = 200L), sourceOrder = 2, firstSeenAtEpochMs = 1L),
        )
        val downloadStates = flowOf(mapOf("/c/2" to ChapterDownload.OnDevice))
        val viewModel = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, downloadStates = downloadStates)
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        assertEquals(listOf("/c/extra", "/c/10", "/c/2"), viewModel.state.value.visibleEntries.map { it.entry.address })

        // Source order toggled to ascending.
        viewModel.setChapterSort(ChapterSort.SOURCE_ORDER)
        advanceUntilIdle()
        assertEquals(listOf("/c/2", "/c/10", "/c/extra"), viewModel.state.value.visibleEntries.map { it.entry.address })
        assertTrue(viewModel.state.value.hasActiveChapterOptions)

        // Chapter number descending puts unnumbered entries at the end; tapping again flips ascending.
        viewModel.setChapterSort(ChapterSort.CHAPTER_NUMBER)
        advanceUntilIdle()
        assertEquals(listOf("/c/10", "/c/2", "/c/extra"), viewModel.state.value.visibleEntries.map { it.entry.address })
        viewModel.setChapterSort(ChapterSort.CHAPTER_NUMBER)
        advanceUntilIdle()
        assertEquals(listOf("/c/2", "/c/10", "/c/extra"), viewModel.state.value.visibleEntries.map { it.entry.address })

        // Upload date descending then ascending.
        viewModel.setChapterSort(ChapterSort.UPLOAD_DATE)
        advanceUntilIdle()
        assertEquals(listOf("/c/extra", "/c/2", "/c/10"), viewModel.state.value.visibleEntries.map { it.entry.address })

        // Unread and Downloaded filters.
        viewModel.setChapterFilter(ChapterFilter.UNREAD)
        advanceUntilIdle()
        assertEquals(listOf("/c/extra", "/c/2"), viewModel.state.value.visibleEntries.map { it.entry.address })
        viewModel.setChapterFilter(ChapterFilter.DOWNLOADED)
        advanceUntilIdle()
        assertEquals(listOf("/c/2"), viewModel.state.value.visibleEntries.map { it.entry.address })

        // Reset restores default sort & filter; search filters by chapter name or translator group.
        viewModel.resetChapterOptions()
        viewModel.setChapterQuery("beta")
        advanceUntilIdle()
        assertEquals(listOf("/c/10"), viewModel.state.value.visibleEntries.map { it.entry.address })
        assertEquals(3, viewModel.state.value.entries.size)
        collecting.cancel()
    }

    @Test
    fun markAllReadAndWebUrlUseRepositoryAndActiveSource() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"))
        val entries = listOf(StoredSeriesEntry(SeriesEntry("/c/1", "Chapter 1"), sourceOrder = 0, firstSeenAtEpochMs = 1L))
        val repository = Restored(stored, entries)
        val source = WebSeriesSource(id.source, "https://site.example")
        val viewModel = SeriesPageViewModel(id, repository, loadSources = { listOf(source) })
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        assertEquals("https://site.example/title/1", viewModel.state.value.webUrl)

        viewModel.markAllRead(true)
        advanceUntilIdle()
        assertEquals(listOf(true), repository.markedAll)

        viewModel.markAllRead(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), repository.markedAll)
        collecting.cancel()
    }
}

/** Holds what a restore stored and records mark-all calls. */
private class Restored(private val stored: StoredSeries, entries: List<StoredSeriesEntry>) : SeriesRepository {
    private val entryFlow = MutableStateFlow(entries)
    val markedAll = mutableListOf<Boolean>()

    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = flowOf(stored)
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = entryFlow
    override suspend fun series(id: SeriesId): StoredSeries? = stored
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = entryFlow.value
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) {
        markedAll += read
        entryFlow.value = entryFlow.value.map { it.copy(read = read, lastPage = if (read) it.lastPage else 0) }
    }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, page: Int, pageCount: Int?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}

private class WebSeriesSource(override val key: SourceKey, private val baseUrl: String) : SeriesSource {
    override val name = "WebSource"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series) = emptyList<SeriesEntry>()
    override suspend fun pages(entry: SeriesEntry) = emptyList<ImageRef>()
    override fun webUrl(series: Series): String = "$baseUrl${series.id.address}"
}
