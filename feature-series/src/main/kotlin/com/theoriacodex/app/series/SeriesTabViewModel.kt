package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one source lists: still loading, failed, or the series loaded so far. */
sealed interface SeriesListing {
    data object Loading : SeriesListing

    data class Failed(val message: String) : SeriesListing

    /** [nextPage] is null once the source has no more; [moreFailed] holds the next page back until Retry. */
    data class Loaded(
        val series: List<Series>,
        val nextPage: Int?,
        val loadingMore: Boolean = false,
        val moreFailed: Boolean = false,
    ) : SeriesListing
}

data class SeriesSourceListing(val key: SourceKey, val name: String, val listing: SeriesListing)

/**
 * The Series tab. [sources] is null until the extensions have loaded; [browse] is the chosen source's popular series;
 * [search] is one listing per source while a search shows, and null otherwise; a search sent before the extensions
 * have loaded is empty until they have, and then runs.
 */
data class SeriesTabState(
    val sources: List<SeriesSource>? = null,
    val browse: SeriesSourceListing? = null,
    val search: List<SeriesSourceListing>? = null,
)

class SeriesTabViewModel(
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SeriesTabState())
    val state: StateFlow<SeriesTabState> = mutableState

    val followed: StateFlow<List<FollowedSeries>> =
        repository.observeFollowed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The text of the search on screen; a result for any other text is dropped. */
    private var searchText: String? = null

    init {
        viewModelScope.launch {
            val sources = loadSources()
            mutableState.update { it.copy(sources = sources) }
            sources.firstOrNull()?.let { browse(it.key) }
            // A search sent while the extensions were loading has waited for them.
            searchText?.let(::search)
        }
    }

    /** Shows [key]'s popular series from the first page. */
    fun browse(key: SourceKey) {
        val source = source(key) ?: return
        mutableState.update { it.copy(browse = SeriesSourceListing(key, source.name, SeriesListing.Loading)) }
        viewModelScope.launch {
            val listing = firstPage { source.popular(1) }
            updateBrowse(key) { listing }
        }
    }

    /** Loads the browsed source's next page, unless one is loading or none is left. */
    fun browseMore() {
        val browse = state.value.browse ?: return
        val loaded = browse.listing as? SeriesListing.Loaded ?: return
        val page = loaded.nextPage?.takeUnless { loaded.loadingMore } ?: return
        val source = source(browse.key) ?: return
        updateBrowse(browse.key) { loaded.copy(loadingMore = true, moreFailed = false) }
        viewModelScope.launch {
            val next = try {
                source.popular(page).let { more ->
                    loaded.copy(series = (loaded.series + more.series).distinctBy(Series::id), nextPage = (page + 1).takeIf { more.hasNextPage })
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                loaded.copy(moreFailed = true)
            }
            updateBrowse(browse.key) { next }
        }
    }

    /** Searches every source for [text] at once; blank text ends the search. */
    fun search(text: String) {
        val query = text.trim().ifEmpty { return clearSearch() }
        val sources = state.value.sources.orEmpty()
        searchText = query
        mutableState.update { it.copy(search = sources.map { source -> SeriesSourceListing(source.key, source.name, SeriesListing.Loading) }) }
        sources.forEach { searchIn(it, query) }
    }

    fun retrySearch(key: SourceKey) {
        val query = searchText ?: return
        val source = source(key) ?: return
        updateSearch(key, query, SeriesListing.Loading)
        searchIn(source, query)
    }

    fun clearSearch() {
        searchText = null
        mutableState.update { it.copy(search = null) }
    }

    /** Stores [series], so its page has something to show at once, then reports it stored. */
    fun open(series: Series, opened: (SeriesId) -> Unit) {
        viewModelScope.launch {
            repository.store(series)
            opened(series.id)
        }
    }

    private fun searchIn(source: SeriesSource, query: String) {
        viewModelScope.launch {
            val listing = firstPage { source.search(query, 1) }
            updateSearch(source.key, query, listing)
        }
    }

    private fun updateSearch(key: SourceKey, query: String, listing: SeriesListing) {
        if (searchText != query) return
        mutableState.update { state ->
            state.copy(search = state.search?.map { if (it.key == key) it.copy(listing = listing) else it })
        }
    }

    private fun updateBrowse(key: SourceKey, listing: () -> SeriesListing) {
        mutableState.update { state -> if (state.browse?.key == key) state.copy(browse = state.browse.copy(listing = listing())) else state }
    }

    private fun source(key: SourceKey) = state.value.sources?.firstOrNull { it.key == key }
}

/** A source's first page as a listing; any failure but cancellation becomes [SeriesListing.Failed]. */
private suspend fun firstPage(load: suspend () -> SeriesPage): SeriesListing = try {
    load().let { SeriesListing.Loaded(it.series, nextPage = 2.takeIf { _ -> it.hasNextPage }) }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    SeriesListing.Failed(error.message?.takeIf(String::isNotBlank) ?: LOAD_FAILED)
}

internal const val LOAD_FAILED = "Couldn't load"
