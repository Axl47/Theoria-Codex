package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.data.repository.matchKey
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A series found on another source; [entries] is null while its count loads, or when that failed. */
data class SwitchMatch(val series: Series, val entries: Int? = null)

/** What one other source found: still searching, failed, or its best matches, none when it found nothing. */
sealed interface SwitchListing {
    data object Loading : SwitchListing

    data class Failed(val message: String) : SwitchListing

    data class Found(val matches: List<SwitchMatch>) : SwitchListing
}

data class SwitchSourceResult(val key: SourceKey, val name: String, val listing: SwitchListing)

/**
 * The open Switch source sheet: one result per other source, in the order the sources are offered. [confirming] is the
 * match awaiting confirmation; [failure] says why the last switch did not happen.
 */
data class SwitchSourceSheet(
    val results: List<SwitchSourceResult>,
    val confirming: SwitchMatch? = null,
    val switching: Boolean = false,
    val failure: String? = null,
) {
    fun sourceName(key: SourceKey): String = results.firstOrNull { it.key == key }?.name.orEmpty()
}

/**
 * [others] are the sources that are on besides the series' own, of its kind: progress moves only between lists of the
 * same kind of entry. [sheet] is null while the sheet is closed.
 */
data class SwitchSourceState(val others: List<SeriesSource> = emptyList(), val sheet: SwitchSourceSheet? = null)

/**
 * Finds the series [from] on every other source that is on, and moves it to the one the user picks. [sourcesChanged]
 * emits once at the start and again whenever [loadSources] may answer differently. [store] stores a match before its
 * page opens; [switch] moves [from] to a match on its source, returns whether this call moved it, and throws what the
 * source throws. While a switch runs, the sheet stays open and ignores everything but its outcome.
 */
class SwitchSourceViewModel(
    private val from: SeriesId,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val store: suspend (Series) -> Unit,
    private val switch: suspend (SeriesSource, Series) -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SwitchSourceState())
    val state: StateFlow<SwitchSourceState> = mutableState
    private val others get() = state.value.others

    /** Every source that is on, the series' own too, which the labels tell apart. */
    private var sources = emptyList<SeriesSource>()
    private var title = ""
    private val switching get() = state.value.sheet?.switching == true

    /** Parent of every source call the open sheet makes; closing the sheet cancels them all. */
    private var searching: Job? = null

    /** Sources answer slowly and share rate limits, so only a few calls run at once. */
    private val calls = Semaphore(MAX_SOURCE_CALLS)

    init {
        viewModelScope.launch {
            sourcesChanged.collectLatest {
                sources = loadSources()
                mutableState.update { state -> state.copy(others = sources.filter { it.key != from.source && it.kind == from.source.seriesKind }) }
            }
        }
    }

    /** Opens the sheet and searches every other source for [title]. */
    fun open(title: String) {
        if (switching) return
        closeSheet()
        this.title = title
        val job = SupervisorJob(viewModelScope.coroutineContext[Job]).also { searching = it }
        mutableState.update { state ->
            state.copy(sheet = SwitchSourceSheet(others.map { SwitchSourceResult(it.key, it.label(sources), SwitchListing.Loading) }))
        }
        others.forEach { search(it, job) }
    }

    fun retry(key: SourceKey) {
        if (switching) return
        val job = searching ?: return
        val source = others.firstOrNull { it.key == key } ?: return
        updateResult(key) { it.copy(listing = SwitchListing.Loading) }
        search(source, job)
    }

    fun close() {
        if (!switching) closeSheet()
    }

    private fun closeSheet() {
        searching?.cancel()
        searching = null
        mutableState.update { it.copy(sheet = null) }
    }

    /** Asks to confirm switching to [match]; null dismisses the question. */
    fun confirm(match: SwitchMatch?) = updateSheet { if (it.switching) it else it.copy(confirming = match) }

    /** Stores [match] and closes the sheet, then hands its id to [opened] to show its page; nothing is moved. */
    fun openMatch(match: SwitchMatch, opened: (SeriesId) -> Unit) {
        if (switching) return
        viewModelScope.launch {
            store(match.series)
            close()
            opened(match.series.id)
        }
    }

    /**
     * Moves the series to [match]. Once moved, closes the sheet and hands the new id to [switched]; when another switch
     * moved it first, only closes the sheet.
     */
    fun switchTo(match: SwitchMatch, switched: (SeriesId) -> Unit) {
        if (switching) return
        val source = others.firstOrNull { it.key == match.series.id.source } ?: return
        updateSheet { it.copy(confirming = null, switching = true, failure = null) }
        viewModelScope.launch {
            val moved = try {
                switch(source, match.series)
            } catch (error: CancellationException) {
                throw error
            } catch (_: NoChaptersToMoveToException) {
                return@launch updateSheet { it.copy(switching = false, failure = from.source.seriesKind.words.noneToMoveTo) }
            } catch (error: Exception) {
                return@launch updateSheet { it.copy(switching = false, failure = failureLine(error)) }
            }
            closeSheet()
            if (moved) switched(match.series.id)
        }
    }

    /** Searches [source] for the title, then counts the entries of each match shown, each as its own call. */
    private fun search(source: SeriesSource, job: Job) {
        val query = title
        viewModelScope.launch(job) {
            val listing = calls.withPermit { firstPage { source.search(query, 1) } }
            val matches = (listing as? SeriesListing.Loaded)?.let { bestMatches(query, it.series) }
            updateResult(source.key) {
                it.copy(listing = matches?.let { found -> SwitchListing.Found(found.map(::SwitchMatch)) } ?: SwitchListing.Failed((listing as SeriesListing.Failed).message))
            }
            matches?.forEach { series -> launch { entryCount(source, series)?.let { count -> setEntries(source.key, series.id, count) } } }
        }
    }

    private suspend fun entryCount(source: SeriesSource, series: Series): Int? = calls.withPermit {
        try {
            source.entries(series).size
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun setEntries(key: SourceKey, id: SeriesId, count: Int) = updateResult(key) { result ->
        val found = result.listing as? SwitchListing.Found ?: return@updateResult result
        result.copy(listing = SwitchListing.Found(found.matches.map { if (it.series.id == id) it.copy(entries = count) else it }))
    }

    private fun updateResult(key: SourceKey, change: (SwitchSourceResult) -> SwitchSourceResult) =
        updateSheet { sheet -> sheet.copy(results = sheet.results.map { if (it.key == key) change(it) else it }) }

    private fun updateSheet(change: (SwitchSourceSheet) -> SwitchSourceSheet) =
        mutableState.update { state -> state.copy(sheet = state.sheet?.let(change)) }
}

/** The [MAX_MATCHES] of [found] most likely to be [title]: the same title by [matchKey] first, then the source's order. */
internal fun bestMatches(title: String, found: List<Series>): List<Series> {
    val key = matchKey(title)
    return found.sortedByDescending { matchKey(it.title) == key }.take(MAX_MATCHES)
}

private const val MAX_MATCHES = 3
private const val MAX_SOURCE_CALLS = 4
