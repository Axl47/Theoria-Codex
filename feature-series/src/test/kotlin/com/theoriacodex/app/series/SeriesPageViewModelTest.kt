package com.theoriacodex.app.series

import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.DownloadScheduler
import com.theoriacodex.data.repository.EntryProgressRestore
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
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.UnsupportedEntriesException
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
        val viewModel = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))
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
        val viewModel = SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), loadSources = { asked++; emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))
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
        val viewModel = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, downloadStates = downloadStates, ioDispatcher = StandardTestDispatcher(testScheduler))
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
            StoredSeriesEntry(SeriesEntry("/c/1", "Chapter 1"), sourceOrder = 2, firstSeenAtEpochMs = 1L, position = 4, length = 10),
        )
        val repository = Restored(stored, entries)
        val source = WebSeriesSource(id.source, "https://site.example")
        val viewModel = SeriesPageViewModel(id, repository, loadSources = { listOf(source) }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        assertEquals("https://site.example/title/1", viewModel.state.value.webUrl)

        // Single chapter mark read.
        viewModel.setEntryRead("/c/2", true)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), viewModel.state.value.entries.map { it.read })

        // Mark previous chapters from Chapter 3 marks only unread chapters earlier in reading order (/c/1).
        var prevUndo: BulkReadUndo? = null
        viewModel.markPreviousRead("/c/3") { prevUndo = it }
        advanceUntilIdle()
        assertNotNull(prevUndo)
        assertEquals(listOf(false, true, true), viewModel.state.value.entries.map { it.read })

        // Separate edit to Chapter 3 must survive undoing the earlier Mark previous action.
        viewModel.setEntryRead("/c/3", true)
        advanceUntilIdle()
        viewModel.undoBulkRead(prevUndo!!)
        advanceUntilIdle()
        assertEquals(listOf(true, true, false), viewModel.state.value.entries.map { it.read })
        assertEquals(4, viewModel.state.value.entries.last().position)

        // Mark all read + undo.
        var allUndo: BulkReadUndo? = null
        viewModel.markAllRead(true) { allUndo = it }
        advanceUntilIdle()
        assertNotNull(allUndo)
        assertTrue(viewModel.state.value.entries.all { it.read })

        var noopUndo: BulkReadUndo? = null
        viewModel.markAllRead(true) { noopUndo = it }
        advanceUntilIdle()
        assertNull(noopUndo)

        viewModel.undoBulkRead(allUndo!!)
        advanceUntilIdle()
        assertEquals(listOf(true, true, false), viewModel.state.value.entries.map { it.read })
        collecting.cancel()
    }

    @Test
    fun chapterDisplayPreferencesPersistAcrossViewModelsWithoutPersistingQuery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val stored = StoredSeries(Series(id, "Title"))
        val entries = listOf(StoredSeriesEntry(SeriesEntry("/c/1", "Chapter 1"), sourceOrder = 0, firstSeenAtEpochMs = 1L))
        val prefs = MemoryChapterPreferences()

        val first = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, chapterPreferences = prefs, ioDispatcher = StandardTestDispatcher(testScheduler))
        first.setChapterSort(ChapterSort.CHAPTER_NUMBER)
        first.setChapterSort(ChapterSort.CHAPTER_NUMBER) // ascending
        first.setChapterFilter(ChapterFilter.UNREAD)
        first.setChapterQuery("boss")

        val reopened = SeriesPageViewModel(id, Restored(stored, entries), loadSources = { emptyList() }, chapterPreferences = prefs, ioDispatcher = StandardTestDispatcher(testScheduler))
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
        val viewModel = SeriesPageViewModel(id, Restored(stored, emptyList()), loadSources = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        viewModel.refresh(userInitiated = true)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.userRefreshing)
        assertFalse(viewModel.state.value.refreshing)
        collecting.cancel()
    }

    @Test
    fun anAnimeOffersNoDownloads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        fun page(source: String): SeriesPageViewModel {
            val id = SeriesId(SourceKey.parseOrThrow(source), "/title/1")
            return SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), { emptyList() }, downloads = downloads(), ioDispatcher = StandardTestDispatcher(testScheduler))
        }

        assertTrue(page("custom_mihon_7").canDownload)
        assertFalse(page("custom_aniyomi_7").canDownload)
    }

    /** The undo bar names what happened in the kind's own words. */
    @Test
    fun markingAnAnimeSaysWatched() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/title/1")
        val entries = listOf(
            StoredSeriesEntry(SeriesEntry("/e/2", "Episode 2"), sourceOrder = 0, firstSeenAtEpochMs = 1L),
            StoredSeriesEntry(SeriesEntry("/e/1", "Episode 1"), sourceOrder = 1, firstSeenAtEpochMs = 1L),
        )
        val viewModel = SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), entries), loadSources = { emptyList() }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val collecting = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        var undo: BulkReadUndo? = null
        viewModel.markPreviousRead("/e/2") { undo = it }
        advanceUntilIdle()
        assertEquals("Marked 1 episode as watched", undo?.label)
        viewModel.markAllRead(false) { undo = it }
        advanceUntilIdle()
        assertEquals("Marked all as unwatched", undo?.label)
        collecting.cancel()
    }

    /** As an Aniyomi source that groups an anime's episodes into seasons reports it. */
    @Test
    fun aSourceThatCannotListTheEntriesSaysWhyInItsOwnWordsWithoutRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val id = SeriesId(SourceKey.parseOrThrow("custom_aniyomi_7"), "/title/1")
        val source = Seasons(id.source)
        val viewModel = SeriesPageViewModel(id, Restored(StoredSeries(Series(id, "Title")), emptyList()), loadSources = { listOf(source) }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val collecting = launch { viewModel.state.collect {} }

        advanceUntilIdle()
        // Trying again cannot help, so the page offers no Retry.
        assertEquals(RefreshFailure(Seasons.MESSAGE, retryable = false), viewModel.state.value.failure)
        collecting.cancel()
    }

    /** Downloads over this test's folder that never run: the page only asks whether it offers them. */
    private fun downloads() = SeriesDownloads(
        store = OfflineMediaStore(folder.newFolder()),
        queueFile = folder.root.resolve("queue.json"),
        repository = Restored(StoredSeries(Series(SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/"), "")), emptyList()),
        source = { null },
        covers = SeriesCovers.Stored,
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
    override suspend fun entries(series: Series, stored: List<SeriesEntry>): SeriesEntries = throw UnsupportedEntriesException(MESSAGE)
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Streams(emptyList())

    companion object {
        const val MESSAGE = "Anime split into seasons aren't supported yet"
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
    override suspend fun storeEntries(id: SeriesId, entries: List<SeriesEntry>, seriesMemo: String?) = emptyList<SeriesEntry>()
    override suspend fun setFollowed(id: SeriesId, followed: Boolean) = Unit
    override suspend fun setReadingMode(id: SeriesId, mode: GalleryReadingMode) = Unit
    override suspend fun setDownloadNewChapters(id: SeriesId, on: Boolean) = Unit
    override suspend fun setChaptersShareFirstPage(id: SeriesId) = Unit
    override suspend fun setAllRead(id: SeriesId, read: Boolean): EntryProgressRestore {
        val changed = entryFlow.value.filter { if (read) !it.read else (it.read || it.position > 0) }
        val prevLast = storedFlow.value?.lastOpenedEntry
        val clearLast = !read && prevLast != null
        entryFlow.value = entryFlow.value.map { it.copy(read = read, position = if (read) it.position else 0) }
        if (clearLast) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
        return EntryProgressRestore(changed, restoreLastOpened = clearLast, lastOpenedEntry = prevLast)
    }

    override suspend fun setEntriesRead(id: SeriesId, entryAddresses: List<String>, read: Boolean): EntryProgressRestore {
        val targets = entryAddresses.toHashSet()
        val changed = entryFlow.value.filter { it.entry.address in targets && (if (read) !it.read else (it.read || it.position > 0)) }
        val prevLast = storedFlow.value?.lastOpenedEntry
        val clearLast = !read && prevLast in targets
        entryFlow.value = entryFlow.value.map { entry ->
            if (entry.entry.address !in targets) entry
            else entry.copy(read = read, position = if (read) entry.position else 0)
        }
        if (clearLast) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = null)
        return EntryProgressRestore(changed, restoreLastOpened = clearLast, lastOpenedEntry = prevLast)
    }

    override suspend fun restoreProgress(id: SeriesId, restore: EntryProgressRestore) {
        val byAddress = restore.entries.associateBy { it.entry.address }
        entryFlow.value = entryFlow.value.map { current ->
            val target = byAddress[current.entry.address] ?: return@map current
            current.copy(read = target.read, position = target.position, length = target.length)
        }
        if (restore.restoreLastOpened) storedFlow.value = storedFlow.value?.copy(lastOpenedEntry = restore.lastOpenedEntry)
    }

    override suspend fun recordProgress(id: SeriesId, entryAddress: String, position: Long, length: Long?) = Unit
    override suspend fun moveSeries(from: SeriesId, to: Series, entries: List<SeriesEntry>) = false
}

private class WebSeriesSource(override val key: SourceKey, private val baseUrl: String) : SeriesSource {
    override val name = "WebSource"
    override val language = "en"
    override suspend fun popular(page: Int) = SeriesPage(emptyList(), false)
    override suspend fun search(text: String, page: Int, filters: List<SeriesFilter>) = SeriesPage(emptyList(), false)
    override suspend fun details(series: Series) = series
    override suspend fun entries(series: Series, stored: List<SeriesEntry>) = SeriesEntries(emptyList())
    override suspend fun media(entry: SeriesEntry) = EntryMedia.Pages(emptyList())
    override fun webUrl(series: Series): String = "$baseUrl${series.id.address}"
}
