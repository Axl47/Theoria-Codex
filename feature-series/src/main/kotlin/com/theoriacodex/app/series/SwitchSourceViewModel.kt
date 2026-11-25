package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.NoChaptersToMoveToException
import com.theoriacodex.data.repository.SwitchSourceSkips
import com.theoriacodex.data.repository.matchKey
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class SwitchMatch(val series: Series, val entries: Int? = null)

sealed interface SwitchListing {
    data object Loading : SwitchListing

    data class Failed(val message: String) : SwitchListing

    data class Found(val matches: List<SwitchMatch>) : SwitchListing
}

data class SwitchSourceResult(val key: SourceKey, val name: String, val listing: SwitchListing)

data class SwitchSourceSheet(
    val results: List<SwitchSourceResult>,
    val query: String = "",
    val skipped: Set<SourceKey> = emptySet(),
    val confirming: SwitchMatch? = null,
    val switching: Boolean = false,
    val failure: String? = null,
) {
    fun sourceName(key: SourceKey): String = results.firstOrNull { it.key == key }?.name.orEmpty()
}

data class SwitchSourceState(val others: List<SeriesSource> = emptyList(), val sheet: SwitchSourceSheet? = null)

class SwitchSourceViewModel(
    private val from: SeriesId,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val store: suspend (Series) -> Unit,
    private val choices: SwitchSourceSkips,
    private val switch: suspend (SeriesSource, Series) -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SwitchSourceState())
    val state: StateFlow<SwitchSourceState> = mutableState
    private val others get() = state.value.others

    private var sources = emptyList<SeriesSource>()

    private var searched = ""
    private val switching get() = state.value.sheet?.switching == true

    private var searching: Job? = null
    private val searches = mutableMapOf<SourceKey, Job>()

    private var storedSkips = emptySet<SourceKey>()
    private val saves = Mutex()

    private val calls = Semaphore(MAX_SOURCE_CALLS)

    init {
        viewModelScope.launch { choices.observeSwitchSourceSkipped().collect { storedSkips = it } }
        viewModelScope.launch {
            sourcesChanged.collectLatest {
                sources = loadSources()
                storedSkips = choices.observeSwitchSourceSkipped().first()
                mutableState.update { state -> state.copy(others = sources.filter { it.key != from.source && it.kind == from.source.seriesKind }) }
            }
        }
    }

    fun open(title: String) {
        if (switching) return
        closeSheet()
        mutableState.update { it.copy(sheet = SwitchSourceSheet(results = emptyList(), query = title, skipped = storedSkips)) }
        searchAll(title)
    }

    fun toggle(keys: Collection<SourceKey>) {
        val sheet = state.value.sheet ?: return
        if (sheet.switching) return
        val skipped = sheet.skipped.toggled(keys)
        val added = keys.filter { it in sheet.skipped && it !in skipped }
        keys.filter { it in skipped }.forEach { searches.remove(it)?.cancel() }
        updateSheet { current ->
            val shown = current.results.associateBy(SwitchSourceResult::key)
            current.copy(
                skipped = skipped,
                results = others.filter { it.key !in skipped }.map { shown[it.key] ?: SwitchSourceResult(it.key, it.label(sources), SwitchListing.Loading) },
            )
        }
        keep(keys, skipped)
        val job = searching ?: return
        others.filter { it.key in added }.forEach { search(it, job) }
    }

    private fun keep(changed: Collection<SourceKey>, skipped: Set<SourceKey>) {
        val (off, on) = changed.toSet().partition(skipped::contains)
        viewModelScope.launch { withContext(NonCancellable) { saves.withLock { choices.updateSwitchSourceSkipped { it - on.toSet() + off } } } }
    }

    fun editQuery(query: String) = updateSheet { it.copy(query = query) }

    fun searchAgain() {
        val query = state.value.sheet?.query?.trim().orEmpty()
        if (!switching && query.isNotEmpty()) searchAll(query)
    }

    private fun searchAll(query: String) {
        searching?.cancel()
        searched = query
        searches.clear()
        val job = SupervisorJob(viewModelScope.coroutineContext[Job]).also { searching = it }
        val checked = others.filter { it.key !in state.value.sheet?.skipped.orEmpty() }
        updateSheet { sheet ->
            sheet.copy(results = checked.map { SwitchSourceResult(it.key, it.label(sources), SwitchListing.Loading) }, confirming = null, failure = null)
        }
        checked.forEach { search(it, job) }
    }

    fun retry(key: SourceKey) {
        if (switching) return
        val job = searching ?: return
        if (state.value.sheet?.results?.firstOrNull { it.key == key }?.listing !is SwitchListing.Failed) return
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
        searches.clear()
        mutableState.update { it.copy(sheet = null) }
    }

    fun confirm(match: SwitchMatch?) = updateSheet { if (it.switching) it else it.copy(confirming = match) }

    fun openMatch(match: SwitchMatch, opened: (SeriesId) -> Unit) {
        if (switching) return
        viewModelScope.launch {
            store(match.series)
            close()
            opened(match.series.id)
        }
    }

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

    private fun search(source: SeriesSource, job: Job) {
        val query = searched
        searches[source.key] = viewModelScope.launch(job) {
            val listing = calls.withPermit { firstPage { source.search(query, 1) } }
            val matches = (listing as? SeriesListing.Loaded)?.let { bestMatches(query, it.series) }
            updateResult(source.key) {
                it.copy(listing = matches?.let { found -> SwitchListing.Found(found.map(::SwitchMatch)) } ?: SwitchListing.Failed((listing as SeriesListing.Failed).message))
            }
            matches?.forEach { series -> launch { entryCount(source, series)?.let { count -> setEntries(source.key, series.id, count) } } }
        }
    }

    private suspend fun entryCount(source: SeriesSource, series: Series): Int? = calls.withPermit {
        runCatchingExceptionsPreservingCancellation {
            source.entries(series, stored = emptyList()).entries.size
        }.getOrNull()
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

internal fun bestMatches(title: String, found: List<Series>): List<Series> {
    val key = matchKey(title)
    return found.sortedByDescending { matchKey(it.title) == key }.take(MAX_MATCHES)
}

private const val MAX_MATCHES = 3
private const val MAX_SOURCE_CALLS = 4
