package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.ChapterDownload
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.data.repository.refresh
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the chapter list on a series page is ordered. */
enum class ChapterSort { SOURCE_ORDER, CHAPTER_NUMBER, UPLOAD_DATE }

/** Which chapters on a series page are shown. */
enum class ChapterFilter { ALL, UNREAD, DOWNLOADED }

private data class ChapterViewOptions(
    val sort: ChapterSort = ChapterSort.SOURCE_ORDER,
    val descending: Boolean = true,
    val filter: ChapterFilter = ChapterFilter.ALL,
    val query: String = "",
)

/**
 * One series' page: what is stored of it, its entries in the source's order (newest first), the filtered and sorted
 * [visibleEntries] for display, and how the refresh from its source is going. A failed refresh leaves the stored
 * series and entries on screen. So does a series whose source is [sourceMissing], but its chapters cannot be opened
 * until the extension is back.
 */
data class SeriesPageState(
    val stored: StoredSeries? = null,
    val entries: List<StoredSeriesEntry> = emptyList(),
    /** [entries] narrowed by [chapterFilter] and [chapterQuery], then ordered by [chapterSort] and [sortDescending]. */
    val visibleEntries: List<StoredSeriesEntry> = emptyList(),
    /** The series' source while it is on. */
    val source: SeriesSource? = null,
    /** What the screens call [source], by [label]; blank without it. */
    val sourceName: String = "",
    val refreshing: Boolean = true,
    val failure: String? = null,
    /**
     * No source on offer is the series' own. The app cannot tell why: its extension may be off, removed or blocked,
     * or the source's language off.
     */
    val sourceMissing: Boolean = false,
    /** Each chapter queued for download or on the device, by address; empty in a build that cannot download. */
    val downloads: Map<String, ChapterDownload> = emptyMap(),
    val chapterSort: ChapterSort = ChapterSort.SOURCE_ORDER,
    val sortDescending: Boolean = true,
    val chapterFilter: ChapterFilter = ChapterFilter.ALL,
    val chapterQuery: String = "",
) {
    /** Whether the chapter at [entry] can be read now: through its source, or from the device without one. */
    fun canOpen(entry: String): Boolean = !sourceMissing || downloads[entry] == ChapterDownload.OnDevice

    /** The entry Continue opens. */
    val continueWith: StoredSeriesEntry? get() = stored?.let { continueEntry(it, entries) }

    /** Whether any entry has been opened, which is what makes the button Continue. */
    val started: Boolean get() = entries.any { it.read || it.lastPage > 0 }

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
 * [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently; each is a refresh.
 * Without [downloads] the page offers none.
 */
class SeriesPageViewModel(
    private val id: SeriesId,
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val downloads: SeriesDownloads? = null,
    downloadStates: Flow<Map<String, ChapterDownload>> = downloads?.states(id) ?: flowOf(emptyMap()),
) : ViewModel() {
    private val refresh = MutableStateFlow(SeriesPageState())
    private val chapterOptions = MutableStateFlow(ChapterViewOptions())

    /** Whether this page offers downloads. */
    val canDownload: Boolean = downloads != null

    val state: StateFlow<SeriesPageState> = combine(
        repository.observeSeries(id),
        repository.observeEntries(id),
        refresh,
        downloadStates,
        chapterOptions,
    ) { stored, entries, refresh, downloads, options ->
        val ordered = entries.sortedBy { it.sourceOrder }
        val visible = applyChapterOptions(ordered, downloads, options)
        refresh.copy(
            stored = stored,
            entries = ordered,
            visibleEntries = visible,
            downloads = downloads,
            chapterSort = options.sort,
            sortDescending = options.descending,
            chapterFilter = options.filter,
            chapterQuery = options.query,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesPageState())

    /** The refresh running, which a newer one replaces. */
    private var refreshJob: Job? = null

    /** True while a move to another source runs, and for good once it moved: no refresh starts meanwhile. */
    private var moving = false

    init {
        viewModelScope.launch { sourcesChanged.collect { refresh() } }
    }

    /** Fetches the series' details and entries from its source and stores them. */
    fun refresh() {
        if (moving) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refreshNow() }
    }

    /**
     * Runs [move], which moves this series to another source and returns whether it did, with refreshes stopped: one
     * finishing after the move would store the old series again. They stop for good once it moved.
     */
    suspend fun whileMoving(move: suspend () -> Boolean): Boolean {
        moving = true
        refreshJob?.cancelAndJoin()
        refresh.update { it.copy(refreshing = false) }
        var moved = false
        try {
            moved = move()
        } finally {
            moving = moved
        }
        return moved
    }

    private suspend fun refreshNow() {
        refresh.update { it.copy(refreshing = true, failure = null) }
        val sources = loadSources()
        val source = sources.firstOrNull { it.key == id.source }
        if (source == null) {
            refresh.update { it.copy(refreshing = false, source = null, sourceName = "", sourceMissing = true) }
            return
        }
        refresh.update { it.copy(source = source, sourceName = source.label(sources), sourceMissing = false) }
        val failure = try {
            repository.series(id)?.let { repository.refresh(source, it.series) }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }
        refresh.update { it.copy(refreshing = false, failure = failure?.let(::failureLine)) }
    }

    /** Selects [sort]; selecting the active sort flips ascending vs descending order. */
    fun setChapterSort(sort: ChapterSort) {
        chapterOptions.update { current ->
            if (current.sort == sort) current.copy(descending = !current.descending)
            else current.copy(sort = sort, descending = true)
        }
    }

    fun setChapterFilter(filter: ChapterFilter) {
        chapterOptions.update { it.copy(filter = filter) }
    }

    fun resetChapterOptions() {
        chapterOptions.update { it.copy(sort = ChapterSort.SOURCE_ORDER, descending = true, filter = ChapterFilter.ALL) }
    }

    fun setChapterQuery(query: String) {
        chapterOptions.update { it.copy(query = query) }
    }

    fun markAllRead(read: Boolean) {
        viewModelScope.launch { repository.setAllRead(id, read) }
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
