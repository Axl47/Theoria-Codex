package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.data.repository.StoredSeriesEntry
import com.theoriacodex.data.repository.continueEntry
import com.theoriacodex.data.repository.refresh
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
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
 * is [sourceMissing], but its chapters cannot be opened until the extension is back.
 */
data class SeriesPageState(
    val stored: StoredSeries? = null,
    val entries: List<StoredSeriesEntry> = emptyList(),
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
) {
    /** The entry Continue opens. */
    val continueWith: StoredSeriesEntry? get() = stored?.let { continueEntry(it, entries) }

    /** Whether any entry has been opened, which is what makes the button Continue. */
    val started: Boolean get() = entries.any { it.read || it.lastPage > 0 }
}

/** [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently; each is a refresh. */
class SeriesPageViewModel(
    private val id: SeriesId,
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
) : ViewModel() {
    private val refresh = MutableStateFlow(SeriesPageState())

    val state: StateFlow<SeriesPageState> =
        combine(repository.observeSeries(id), repository.observeEntries(id), refresh) { stored, entries, refresh ->
            refresh.copy(stored = stored, entries = entries.sortedBy { it.sourceOrder })
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
            failureLine(error)
        }
        refresh.update { it.copy(refreshing = false, failure = failure) }
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
