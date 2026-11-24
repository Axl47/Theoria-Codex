package com.theoriacodex.app.series

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.EntryProgressRestore
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.data.repository.refresh
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.UnsupportedEntriesException
import com.theoriacodex.domain.series.seriesKind
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the entry list on a series page is ordered. */
enum class ChapterSort { SOURCE_ORDER, CHAPTER_NUMBER, UPLOAD_DATE }

/** Which entries on a series page are shown. */
enum class ChapterFilter { ALL, UNREAD, DOWNLOADED }

private data class ChapterViewOptions(
    val sort: ChapterSort = ChapterSort.SOURCE_ORDER,
    val descending: Boolean = true,
    val filter: ChapterFilter = ChapterFilter.ALL,
    val query: String = "",
)

/** Transaction-captured undo snapshot for a bulk read/unread action. */
data class BulkReadUndo(
    val label: String,
    val restore: EntryProgressRestore,
)

/**
 * One series' page: what is stored of it, its entries in the source's order (newest first), the filtered and sorted
 * [visibleEntries] for display, and how the refresh from its source is going. A failed refresh leaves the stored
 * series and entries on screen. So does a series whose source is [sourceMissing], but its entries cannot be opened
 * until the extension is back.
 */
data class SeriesPageState(
    /** Read from the series' source id, so it is known before anything loads and while the extension is gone. */
    val kind: SeriesKind = SeriesKind.MANGA,
    val stored: StoredSeries? = null,
    val entries: List<StoredSeriesEntry> = emptyList(),
    /** [entries] narrowed by [chapterFilter] and [chapterQuery], then ordered by [chapterSort] and [sortDescending]. */
    val visibleEntries: List<StoredSeriesEntry> = emptyList(),
    /** The series' source while it is on. */
    val source: SeriesSource? = null,
    /** What the screens call [source], by [label]; blank without it. */
    val sourceName: String = "",
    val refreshing: Boolean = true,
    /** True while a manual pull-to-refresh is running; drives the pull indicator without getting stuck on fast failures. */
    val userRefreshing: Boolean = false,
    val failure: RefreshFailure? = null,
    /**
     * No source on offer is the series' own. The app cannot tell why: its extension may be off, removed or blocked,
     * or the source's language off.
     */
    val sourceMissing: Boolean = false,
    /** Each chapter queued for download or on the device, by address; empty when the page offers no downloads. */
    val downloads: Map<String, ChapterDownload> = emptyMap(),
    val chapterSort: ChapterSort = ChapterSort.SOURCE_ORDER,
    val sortDescending: Boolean = true,
    val chapterFilter: ChapterFilter = ChapterFilter.ALL,
    val chapterQuery: String = "",
) {
    /** Whether the entry at [entry] can be opened now: through its source, or from the device without one. */
    fun canOpen(entry: String): Boolean = !sourceMissing || downloads[entry] == ChapterDownload.OnDevice

    /** The entry Continue opens. */
    val continueWith: StoredSeriesEntry? get() = stored?.let { continueEntry(it, entries) }

    /** Whether any entry has been opened, which is what makes the button Continue. */
    val started: Boolean get() = entries.any { it.read || it.position > 0 }

    /** Whether any sort or filter option differs from the default listing. */
    val hasActiveChapterOptions: Boolean
        get() = chapterFilter != ChapterFilter.ALL || chapterSort != ChapterSort.SOURCE_ORDER || !sortDescending

    /** The series' page URL on its source site, for Open in browser and Share. */
    val webUrl: String?
        get() = stored?.series?.let { series ->
            source?.webUrl(series) ?: series.id.address.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            }
        }
}

/**
 * Why the page's last refresh failed, in words for the user. Trying again cannot list a series the source cannot list
 * ([UnsupportedEntriesException]), so the page offers Retry only when [retryable].
 */
data class RefreshFailure(val message: String, val retryable: Boolean) {
    constructor(error: Throwable) : this(failureLine(error), error !is UnsupportedEntriesException)
}

/**
 * [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently; each is a refresh.
 * Without [downloads] the page offers none, and an anime offers none either: episodes do not download yet.
 * [chapterPreferences] persists per-series sort and filter choices, read at the start on [ioDispatcher], which also
 * filters and sorts the entry list off the main thread. A test passes its own dispatcher: on the real one the work can
 * finish after the test, and fail the next test that starts.
 */
class SeriesPageViewModel(
    private val id: SeriesId,
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    downloads: SeriesDownloads? = null,
    downloadStates: Flow<Map<String, ChapterDownload>> = downloads?.states(id) ?: flowOf(emptyMap()),
    private val chapterPreferences: SeriesChapterPreferences = NoOpSeriesChapterPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val kind = id.source.seriesKind
    private val downloads = downloads.takeIf { kind == SeriesKind.MANGA }
    private val refresh = MutableStateFlow(SeriesPageState(kind = kind))
    private val chapterOptions = MutableStateFlow(ChapterViewOptions())
    private var optionsTouched = false

    /** Whether this page offers downloads. */
    val canDownload: Boolean = this.downloads != null

    /** Synchronous search bar state kept alongside [chapterOptions]; survives rotation and resets on process death. */
    var chapterSearchOpen: Boolean by mutableStateOf(false)
        private set
    var chapterSearchInput: String by mutableStateOf("")
        private set

    val state: StateFlow<SeriesPageState> = combine(
        repository.observeSeries(id),
        repository.observeEntries(id),
        refresh,
        if (kind == SeriesKind.MANGA) downloadStates else flowOf(emptyMap()),
        chapterOptions,
    ) { stored, entries, refresh, downloads, options ->
        // The repository lists entries in the source's order already.
        refresh.copy(
            stored = stored,
            entries = entries,
            visibleEntries = applyChapterOptions(entries, downloads, options),
            downloads = downloads,
            chapterSort = options.sort,
            sortDescending = options.descending,
            chapterFilter = options.filter,
            chapterQuery = options.query,
        )
    }.flowOn(ioDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), refresh.value)

    /** The refresh running, which a newer one replaces. */
    private var refreshJob: Job? = null

    /** True while a move to another source runs, and for good once it moved: no refresh starts meanwhile. */
    private var moving = false

    init {
        viewModelScope.launch {
            val saved = withContext(ioDispatcher) { chapterPreferences.read(id) }
            if (!optionsTouched && !saved.isDefault) {
                val filter = if (!canDownload && saved.filter == ChapterFilter.DOWNLOADED) ChapterFilter.ALL else saved.filter
                chapterOptions.update { it.copy(sort = saved.sort, descending = saved.descending, filter = filter) }
            }
        }
        viewModelScope.launch { sourcesChanged.collect { refresh() } }
    }

    /**
     * Fetches the series' details and entries from its source and stores them. New chapters it finds are queued for
     * download as the check for new chapters would queue them; it announces none, though.
     */
    fun refresh(userInitiated: Boolean = false) {
        if (moving) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshNow(userInitiated) }
    }

    /**
     * Runs [move], which moves this series to another source and returns whether it did, with refreshes stopped: one
     * finishing after the move would store the old series again. They stop for good once it moved.
     */
    suspend fun whileMoving(move: suspend () -> Boolean): Boolean {
        moving = true
        refreshJob?.cancelAndJoin()
        refresh.update { it.copy(refreshing = false, userRefreshing = false) }
        var moved = false
        try {
            moved = move()
        } finally {
            moving = moved
        }
        return moved
    }

    private suspend fun refreshNow(userInitiated: Boolean) {
        refresh.update { it.copy(refreshing = true, userRefreshing = userInitiated, failure = null) }
        val sources = loadSources()
        val source = sources.firstOrNull { it.key == id.source }
        if (source == null) {
            refresh.update { it.copy(refreshing = false, userRefreshing = false, source = null, sourceName = "", sourceMissing = true) }
            return
        }
        refresh.update { it.copy(source = source, sourceName = source.label(sources), sourceMissing = false) }
        val failure = runCatchingExceptionsPreservingCancellation {
            repository.series(id)?.let { stored ->
                repository.refresh(source, stored.series) { added -> downloads?.downloadNew(SeriesUpdate(stored.series, added)) }
            }
        }.exceptionOrNull()
        refresh.update { it.copy(refreshing = false, userRefreshing = false, failure = failure?.let(::RefreshFailure)) }
    }

    /** Selects [sort]; selecting the active sort flips ascending vs descending order. */
    fun setChapterSort(sort: ChapterSort) {
        updateDisplayOptions { current ->
            if (current.sort == sort) current.copy(descending = !current.descending)
            else current.copy(sort = sort, descending = true)
        }
    }

    fun resetChapterSort() {
        updateDisplayOptions { it.copy(sort = ChapterSort.SOURCE_ORDER, descending = true) }
    }

    fun setChapterFilter(filter: ChapterFilter) {
        updateDisplayOptions { it.copy(filter = filter) }
    }

    fun resetChapterOptions() {
        updateDisplayOptions { it.copy(sort = ChapterSort.SOURCE_ORDER, descending = true, filter = ChapterFilter.ALL) }
    }

    private fun updateDisplayOptions(transform: (ChapterViewOptions) -> ChapterViewOptions) {
        optionsTouched = true
        val next = chapterOptions.updateAndGet(transform)
        chapterPreferences.write(id, ChapterDisplayPreferences(next.sort, next.descending, next.filter))
    }

    fun openChapterSearch() {
        chapterSearchOpen = true
    }

    fun closeChapterSearch() {
        chapterSearchOpen = false
        setChapterQuery("")
    }

    fun setChapterQuery(query: String) {
        chapterSearchInput = query
        chapterOptions.update { it.copy(query = query) }
    }

    /** Marks a single entry finished or not. */
    fun setEntryRead(entryAddress: String, read: Boolean) {
        viewModelScope.launch { repository.setEntriesRead(id, listOf(entryAddress), read) }
    }

    /** Marks every unfinished entry earlier in reading order than [entryAddress] finished and delivers an undo snapshot. */
    fun markPreviousRead(entryAddress: String, onUndo: (BulkReadUndo) -> Unit) {
        val current = state.value
        val target = current.entries.firstOrNull { it.entry.address == entryAddress } ?: return
        val previousAddresses = current.entries.filter { it.sourceOrder > target.sourceOrder && !it.read }.map { it.entry.address }
        if (previousAddresses.isEmpty()) return
        viewModelScope.launch {
            val restore = repository.setEntriesRead(id, previousAddresses, read = true)
            if (!restore.isEmpty) onUndo(BulkReadUndo("Marked ${kind.words.count(restore.entries.size)} as ${kind.words.done}", restore))
        }
    }

    /** Marks every entry finished or not and delivers a transaction-scoped undo snapshot when anything changed. */
    fun markAllRead(read: Boolean, onUndo: (BulkReadUndo) -> Unit) {
        viewModelScope.launch {
            val restore = repository.setAllRead(id, read)
            if (!restore.isEmpty) {
                val label = "Marked all as ${if (read) kind.words.done else kind.words.notDone}"
                onUndo(BulkReadUndo(label, restore))
            }
        }
    }

    /** Reverses a [BulkReadUndo] snapshot, restoring only what that bulk operation modified. */
    fun undoBulkRead(undo: BulkReadUndo) {
        viewModelScope.launch { repository.restoreProgress(id, undo.restore) }
    }

    /** The chapter row's download mark: downloads the chapter, or stops its download or deletes it from the device. */
    fun toggleDownload(entry: String) = changeDownloads {
        if (state.value.downloads[entry] == null) download(id, listOf(entry)) else delete(id, entry)
    }

    fun download(choice: DownloadChoice) = changeDownloads { download(id, choice) }

    fun deleteDownloads() = changeDownloads { deleteSeries(id) }

    /** Runs [change]; one the disk refuses, when it is full say, is reported and changes nothing on screen. */
    private fun changeDownloads(change: suspend SeriesDownloads.() -> Unit) {
        val downloads = downloads ?: return
        viewModelScope.launch {
            try {
                downloads.change()
            } catch (error: IOException) {
                Diagnostics.report("series", "Downloads could not change", error)
            }
        }
    }

    fun setDownloadNewChapters(on: Boolean) {
        viewModelScope.launch { repository.setDownloadNewChapters(id, on) }
    }

    /** Follows or unfollows the series; [onFollowed] runs once it is followed. */
    fun toggleFollowed(onFollowed: () -> Unit) {
        val followed = state.value.stored?.followed ?: return
        viewModelScope.launch {
            repository.setFollowed(id, !followed)
            if (!followed) onFollowed()
        }
    }
}

private inline fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return next
    }
}

private fun applyChapterOptions(
    entries: List<StoredSeriesEntry>,
    downloads: Map<String, ChapterDownload>,
    options: ChapterViewOptions,
): List<StoredSeriesEntry> {
    val needle = options.query.trim()
    val filtered = entries.filter { entry ->
        matchesFilter(entry, downloads, options.filter) && matchesQuery(entry, needle)
    }
    return filtered.sortedWith(chapterComparator(options.sort, options.descending))
}

private fun matchesFilter(
    entry: StoredSeriesEntry,
    downloads: Map<String, ChapterDownload>,
    filter: ChapterFilter,
): Boolean = when (filter) {
    ChapterFilter.ALL -> true
    ChapterFilter.UNREAD -> !entry.read
    ChapterFilter.DOWNLOADED -> downloads[entry.entry.address] == ChapterDownload.OnDevice
}

private fun matchesQuery(entry: StoredSeriesEntry, needle: String): Boolean =
    needle.isEmpty() ||
        entry.entry.name.contains(needle, ignoreCase = true) ||
        entry.entry.translatorGroup?.contains(needle, ignoreCase = true) == true

private fun chapterComparator(sort: ChapterSort, descending: Boolean): Comparator<StoredSeriesEntry> = when (sort) {
    ChapterSort.SOURCE_ORDER -> if (descending) compareBy { it.sourceOrder } else compareByDescending { it.sourceOrder }
    ChapterSort.CHAPTER_NUMBER -> if (descending) {
        compareBy<StoredSeriesEntry>({ it.entry.number == null }, { -(it.entry.number ?: 0f) }, { it.sourceOrder })
    } else {
        compareBy<StoredSeriesEntry>({ it.entry.number == null }, { it.entry.number ?: 0f }, { -it.sourceOrder })
    }
    ChapterSort.UPLOAD_DATE -> if (descending) {
        compareBy<StoredSeriesEntry>({ it.entry.uploadedAtEpochMs == null }, { -(it.entry.uploadedAtEpochMs ?: 0L) }, { it.sourceOrder })
    } else {
        compareBy<StoredSeriesEntry>({ it.entry.uploadedAtEpochMs == null }, { it.entry.uploadedAtEpochMs ?: 0L }, { -it.sourceOrder })
    }
}
