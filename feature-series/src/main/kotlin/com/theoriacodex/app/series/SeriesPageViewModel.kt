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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One series' page: what is stored of it, its entries in the source's order (newest first), and how the refresh from
 * its source is going. A failed refresh leaves the stored series and entries on screen.
 */
data class SeriesPageState(
    val stored: StoredSeries? = null,
    val entries: List<StoredSeriesEntry> = emptyList(),
    val sourceName: String = "",
    val refreshing: Boolean = true,
    val failure: String? = null,
) {
    /** The entry Continue opens. */
    val continueWith: StoredSeriesEntry? get() = stored?.let { continueEntry(it, entries) }

    /** Whether any entry has been opened, which is what makes the button Continue. */
    val started: Boolean get() = entries.any { it.read || it.lastPage > 0 }
}

class SeriesPageViewModel(
    private val id: SeriesId,
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
) : ViewModel() {
    private val refresh = MutableStateFlow(SeriesPageState())

    val state: StateFlow<SeriesPageState> =
        combine(repository.observeSeries(id), repository.observeEntries(id), refresh) { stored, entries, refresh ->
            refresh.copy(stored = stored, entries = entries.sortedBy { it.sourceOrder })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesPageState())

    init {
        refresh()
    }

    /** Fetches the series' details and entries from its source and stores them. */
    fun refresh() {
        refresh.update { it.copy(refreshing = true, failure = null) }
        viewModelScope.launch {
            val failure = try {
                val source = loadSources().firstOrNull { it.key == id.source }
                source?.let { refresh.update { state -> state.copy(sourceName = source.name) } }
                val stored = repository.series(id)
                if (source == null || stored == null) {
                    "The extension for this series is not installed"
                } else {
                    repository.refresh(source, stored.series)
                    null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.message?.takeIf(String::isNotBlank) ?: LOAD_FAILED
            }
            refresh.update { it.copy(refreshing = false, failure = failure) }
        }
    }

    /** Follows or unfollows the series; [onFirstFollowed] runs when it became the only followed series. */
    fun toggleFollowed(onFirstFollowed: () -> Unit) {
        val followed = state.value.stored?.followed ?: return
        viewModelScope.launch {
            val first = !followed && repository.observeFollowed().first().isEmpty()
            repository.setFollowed(id, !followed)
            if (first) onFirstFollowed()
        }
    }
}
