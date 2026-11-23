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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // Upload date descending.
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
    fun perChapterReadMarkPreviousReadUndoAndWebUrlWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"), lastOpenedEntry = "/c/1")
        val entries = listOf(
            StoredSeriesEntry(SeriesEntry("/c/3", "Chapter 3"), sourceOrder = 0, firstSeenAtEpochMs = 1L),
            StoredSeriesEntry(SeriesEntry("/c/2", "Chapter 2"), sourceOrder = 1, firstSeenAtEpochMs = 1L),
            StoredSeriesEntry(SeriesEntry("/c/1", "Chapter 1"), sourceOrder = 2, firstSeenAtEpochMs = 1L, lastPage = 4, pageCount = 10),
        )
        val repository = Restored(stored, entries)
        val source = WebSeriesSource(id.source, "https://site.example")
        val viewModel = SeriesPageViewModel(id, repository, loadSources = { listOf(source) })
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        assertEquals("https://site.example/title/1", viewModel.state.value.webUrl)

        // Single chapter mark read.
        viewModel.setEntryRead("/c/2", true)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), viewModel.state.value.entries.map { it.read })

        // Mark previous chapters from Chapter 3 marks only unread chapters earlier in reading order (/c/1).
        val prevUndo = viewModel.markPreviousRead("/c/3")
        advanceUntilIdle()
        assertNotNull(prevUndo)
        assertEquals(listOf(false, true, true), viewModel.state.value.entries.map { it.read })

        // Undo restores exact prior progress.
        viewModel.undoBulkRead(prevUndo!!)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), viewModel.state.value.entries.map { it.read })
        assertEquals(4, viewModel.state.value.entries.last().lastPage)

        // Mark all read + undo.
        val allUndo = viewModel.markAllRead(true)
        advanceUntilIdle()
        assertNotNull(allUndo)
        assertTrue(viewModel.state.value.entries.all { it.read })
        assertNull(viewModel.markAllRead(true))

        viewModel.undoBulkRead(allUndo!!)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), viewModel.state.value.entries.map { it.read })
        collecting.cancel()
    }

    @Test
    fun chapterDisplayPreferencesPersistAcrossViewModelsWithoutPersistingQuery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"))
        val entries = listOf(StoredSeriesEntry(SeriesEntry("/c/1", "Chapter 1"), sourceOrder = 0, firstSeenAtEpochMs = 1L))
        val prefs = MemoryChapterPreferences()

        val first = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, chapterPreferences = prefs)
        first.setChapterSort(ChapterSort.CHAPTER_NUMBER)
        first.setChapterSort(ChapterSort.CHAPTER_NUMBER) // ascending
        first.setChapterFilter(ChapterFilter.UNREAD)
        first.setChapterQuery("boss")

        val reopened = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, chapterPreferences = prefs)
        val collecting = launch { reopened.state.collect {} }
        advanceUntilIdle()

        assertEquals(ChapterSort.CHAPTER_NUMBER, reopened.state.value.chapterSort)
        assertFalse(reopened.state.value.sortDescending)
        assertEquals(ChapterFilter.UNREAD, reopened.state.value.chapterFilter)
        assertEquals("", reopened.state.value.chapterQuery)
        collecting.cancel()
    }

    @Test
    fun manualRefreshClearsUserRefreshingEvenWhenSourceIsMissingOrFailsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"))
        val viewModel = SeriesPageViewModel(id, Restored(stored, emptyList()), loadSources = { emptyList() })
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.refresh(userInitiated = true)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.userRefreshing)
        assertFalse(viewModel.state.value.refreshing)
        collecting.cancel()
    }
}

private class MemoryChapterPreferences : SeriesChapterPreferences {
    private val map = mutableMapOf<SeriesId, ChapterDisplayPreferences>()
    override fun read(id: SeriesId): ChapterDisplayPreferences = map[id] ?: ChapterDisplayPreferences()
    override fun write(id: SeriesId, preferences: ChapterDisplayPreferences) {
        if (preferences.isDefault) map.remove(id) else map[id] = preferences
    }
}

/** Holds what a restore stored and tracks progress mutations. */
private class Restored(stored: StoredSeries, entries: List<StoredSeriesEntry>) : SeriesRepository {
    private val storedFlow = MutableStateFlow<StoredSeries?>(stored)
    private val entryFlow = MutableStateFlow(entries)

    override fun observeFollowed(): Flow<List<FollowedSeries>> = emptyFlow()
    override fun observeSeries(id: SeriesId): Flow<StoredSeries?> = storedFlow
    override fun observeEntries(id: SeriesId): Flow<List<StoredSeriesEntry>> = entryFlow
    override suspend fun series(id: SeriesId): StoredSeries? = storedFlow.value
    override suspend fun entries(id: SeriesId): List<StoredSeriesEntry> = entryFlow.value
    override suspend fun store(series: Series, entries: List<SeriesEntry>?) = emptyList<SeriesEntry>()
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean) {
        entryFlow.value = entryFlow.value.map { it.copy(read = read, lastPage = if (read) it.lastPage else 0) }
        if (!read) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
    }

    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean) {
        val targets = entryAddresses.toHashSet()
        entryFlow.value = entryFlow.value.map { entry ->
            if (entry.entry.address !in targets) entry
            else entry.copy(read = read, lastPage = if (read) entry.lastPage else 0)
        }
        if (!read && storedFlow.value?.lastOpenedEntry in targets) {
            storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
        }
    }

    override suspend fun restoreProgress(id: SeriesId, entries: List<StoredSeriesEntry>, lastOpenedEntry: String?) {
        val byAddress = entries.associateBy { it.entry.address }
        entryFlow.value = entryFlow.value.map { current ->
            val target = byAddress[current.entry.address] ?: return@map current
            current.copy(read = target.read, lastPage = target.lastPage, pageCount = target.pageCount)
        }
        storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = lastOpenedEntry)
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
