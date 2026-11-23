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
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.UnsupportedEntriesException
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import com.theoriacodex.domain.diagnostics.Diagnostics
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One series' page: what is stored of it, its entries in the source's order (newest first), and how the refresh from
 * its source is going. A failed refresh leaves the stored series and entries on screen. So does a series whose source
 * is [sourceMissing], but its entries cannot be opened until the extension is back.
 */
data class SeriesPageState(
    /** Read from the series' source id, so it is known before anything loads and while the extension is gone. */
    val kind: SeriesKind = SeriesKind.MANGA,
    val stored: StoredSeries? = null,
    val entries: List<StoredSeriesEntry> = emptyList(),
    /** The series' source while it is on. */
    val source: SeriesSource? = null,
    /** What the screens call [source], by [label]; blank without it. */
    val sourceName: String = "",
    val refreshing: Boolean = true,
    val failure: RefreshFailure? = null,
    /**
     * No source on offer is the series' own. The app cannot tell why: its extension may be off, removed or blocked,
     * or the source's language off.
     */
    val sourceMissing: Boolean = false,
    /** Each chapter queued for download or on the device, by address; empty when the page offers no downloads. */
    val downloads: Map<String, ChapterDownload> = emptyMap(),
) {
    /** Whether the entry at [entry] can be opened now: through its source, or from the device without one. */
    fun canOpen(entry: String): Boolean = !sourceMissing || downloads[entry] == ChapterDownload.OnDevice

    /** The entry Continue opens. */
    val continueWith: StoredSeriesEntry? get() = stored?.let { continueEntry(it, entries) }

    /** Whether any entry has been opened, which is what makes the button Continue. */
    val started: Boolean get() = entries.any { it.read || it.position > 0 }
}

/**
 * Why the page's last refresh failed, in words for the user. Trying again cannot list a series the source cannot list
 * ([UnsupportedEntriesException]), so the page offers Retry only when [retryable].
 */
data class RefreshFailure(val message: String, val retryable: Boolean) {
    constructor(error: Exception) : this(failureLine(error), error !is UnsupportedEntriesException)
}

/**
 * [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently; each is a refresh.
 * Without [downloads] the page offers none, and an anime offers none either: episodes do not download yet.
 */
class SeriesPageViewModel(
    private val id: SeriesId,
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    downloads: SeriesDownloads? = null,
) : ViewModel() {
    private val downloads = downloads.takeIf { id.source.seriesKind == SeriesKind.MANGA }
    private val refresh = MutableStateFlow(SeriesPageState(kind = id.source.seriesKind))

    /** Whether this page offers downloads. */
    val canDownload: Boolean = this.downloads != null

    val state: StateFlow<SeriesPageState> = combine(
        repository.observeSeries(id),
        repository.observeEntries(id),
        refresh,
        this.downloads?.states(id) ?: flowOf(emptyMap()),
    ) { stored, entries, refresh, downloads ->
        refresh.copy(stored = stored, entries = entries.sortedBy { it.sourceOrder }, downloads = downloads)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), refresh.value)

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
        refresh.update { it.copy(refreshing = false, failure = failure?.let(::RefreshFailure)) }
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
