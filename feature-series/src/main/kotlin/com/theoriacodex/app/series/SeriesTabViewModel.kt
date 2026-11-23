package com.theoriacodex.app.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.ui.sourceFailureDetail
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.changedFrom
import com.theoriacodex.domain.series.withChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
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

data class SeriesSourceListing(
    val key: SourceKey,
    val name: String,
    val listing: SeriesListing,
    val sourceName: String = name,
    val language: String = "",
)

/** The human-readable language subtitle for a source listing, or null when the source has no language code. */
internal val SeriesSourceListing.languageSubtitle: String?
    get() = language.takeIf(String::isNotBlank)?.let(::languageName)

/** Which of its listings the browsed source shows. */
sealed interface BrowseKind {
    data object Popular : BrowseKind

    data object Latest : BrowseKind

    /** The source's own search for [text] with [filters], of which [changed] differ from the source's defaults. */
    data class Filtered(val text: String, val filters: List<SeriesFilter>, val changed: Int) : BrowseKind {
        /** What the Filter chip counts: the changed filters, and the text when there is one. */
        val active: Int get() = changed + if (text.isEmpty()) 0 else 1
    }
}

/**
 * The open filter sheet, which holds its choices until Search. [defaults] are the active source's filters as it
 * offers them, and [draft] those with the choices made; both are null while they load. [expanded] names the open
 * groups by their place in the tree.
 */
data class FilterSheetState(
    val text: String,
    val defaults: List<SeriesFilter>? = null,
    val draft: List<SeriesFilter>? = null,
    val expanded: Set<String> = emptySet(),
)

/**
 * One source's dedicated search, opened from a global search section header. [run] counts listings shown here so
 * answers from an earlier query or source are dropped.
 */
data class DedicatedSourceState(
    val source: SeriesSourceListing,
    val kind: BrowseKind,
    val run: Int = 0,
)

/**
 * The Series tab. [sources] is null until the extensions that are on have loaded; [browse] is the chosen source's
 * [browseKind] listing, and [browseRun] counts the listings browsed, so what belongs to an earlier one can tell;
 * [filterSheet] is the filter sheet while it is open; [search] is one listing per source while a search shows, and
 * null otherwise; a search sent before the extensions have loaded is empty until they have, and then runs.
 * [dedicated] is a single source's search opened from [search], keeping [search] intact underneath until Back.
 * [refreshing] is true while a refresh by hand checks the followed series for new chapters.
 */
data class SeriesTabState(
    val sources: List<SeriesSource>? = null,
    val browse: SeriesSourceListing? = null,
    val browseKind: BrowseKind = BrowseKind.Popular,
    val browseRun: Int = 0,
    val filterSheet: FilterSheetState? = null,
    val search: List<SeriesSourceListing>? = null,
    val searchQuery: String = "",
    val dedicated: DedicatedSourceState? = null,
    val refreshing: Boolean = false,
)

/**
 * [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently. [saved] keeps the
 * search on screen and the browsed source across process death; both show again once the sources have loaded. The
 * browsed listing, its filters, dedicated search and the filter sheet are kept only in memory: after process death
 * they start again at the source's popular series.
 */
class SeriesTabViewModel(
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** Stores the followed series' new chapters, which changes the shelf's unread counts. */
    private val checkFollowed: suspend () -> Unit,
) : ViewModel() {
    private var searchText: String? = saved[SAVED_SEARCH]

    /** Counts submitted searches; a result asked for under an earlier one is dropped, even when its text is the same. */
    private var searchRun = 0

    /** Counts listings shown in [SeriesTabState.dedicated], across open/close cycles. */
    private var dedicatedRun = 0

    /** The filter sheet's load of the source's filters; a sheet closed or opened again cancels it. */
    private var filterLoad: Job? = null

    /** Follows whether the tab shows; see [followShown]. */
    private var shownWatch: Job? = null
    private val mutableState = MutableStateFlow(SeriesTabState(search = searchText?.let { emptyList() }, searchQuery = searchText.orEmpty()))
    val state: StateFlow<SeriesTabState> = mutableState

    val followed: StateFlow<List<FollowedSeries>> =
        repository.observeFollowed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { sourcesChanged.collectLatest { showSources(loadSources()) } }
    }

    /** Checks the followed series for new chapters, unless a check is already running. */
    fun refresh() {
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                checkFollowed()
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Keeps what is on screen for the sources still on offer, and browses the first when the browsed one is gone. A
     * browsed source that was replaced (its extension updated) is browsed afresh, as if chosen again. A search on
     * screen, or one sent while the extensions were loading, runs in every source that is new or replaced.
     */
    private fun showSources(sources: List<SeriesSource>) {
        val before = state.value.sources.orEmpty()
        val fresh = sources.filter { source -> before.none { it === source } }
        val query = searchText
        val hadDedicated = state.value.dedicated != null
        mutableState.update { state ->
            state.copy(
                sources = sources,
                // Labels are taken again: a source turned on can share a name with one already listed.
                browse = state.browse?.let { browse ->
                    sources.firstOrNull { it.key == browse.key }?.toListing(sources, browse.listing)
                },
                search = query?.let {
                    sources.map { source ->
                        val kept = state.search?.firstOrNull { it.key == source.key && source !in fresh }
                        source.toListing(sources, kept?.listing ?: SeriesListing.Loading)
                    }
                },
                dedicated = state.dedicated?.let { dedicated ->
                    sources.firstOrNull { it.key == dedicated.source.key }?.let { source ->
                        dedicated.copy(source = source.toListing(sources, dedicated.source.listing))
                    }
                },
            )
        }
        if (hadDedicated && state.value.dedicated == null) closeFilters()
        val dedicatedKey = state.value.dedicated?.source?.key
        if (dedicatedKey != null && fresh.any { it.key == dedicatedKey }) {
            closeFilters()
            source(dedicatedKey)?.let { showDedicated(it, BrowseKind.Popular) }
        }
        val browsedKey = state.value.browse?.key
        if (browsedKey != null && fresh.any { it.key == browsedKey }) browse(browsedKey)
        else if (browsedKey == null) {
            val browsed = saved.get<String>(SAVED_BROWSE)
            (sources.firstOrNull { it.key.id == browsed } ?: sources.firstOrNull())?.let { browse(it.key) }
        }
        if (query != null) fresh.forEach { searchIn(it, query) }
    }

    /** Shows [key]'s popular series from the first page; the filters chosen before are dropped. */
    fun browse(key: SourceKey) {
        val source = source(key) ?: return
        saved[SAVED_BROWSE] = key.id
        closeFilters()
        show(source, BrowseKind.Popular)
    }

    /** Shows the browsed source's [kind] listing from the first page. */
    fun choose(kind: BrowseKind) {
        show(browsedSource() ?: return, kind)
    }

    fun retryBrowse() = choose(state.value.browseKind)

    /**
     * Opens [key]'s dedicated search seeded with the active global search query. Reuses results already loaded by
     * global search for [key] when available so the transition is immediate; otherwise fetches page 1.
     */
    fun openDedicatedSearch(key: SourceKey) {
        val sources = state.value.sources.orEmpty()
        val source = source(key) ?: return
        val query = searchText.orEmpty().trim()
        closeFilters()
        val kind = if (query.isNotEmpty()) BrowseKind.Filtered(query, emptyList(), changed = 0) else BrowseKind.Popular
        val existing = state.value.search?.firstOrNull { it.key == key }?.listing as? SeriesListing.Loaded
        if (query.isNotEmpty() && existing != null) {
            dedicatedRun++
            val run = dedicatedRun
            mutableState.update { it.copy(dedicated = DedicatedSourceState(source.toListing(sources, existing), kind, run)) }
        } else {
            showDedicated(source, kind)
        }
    }

    /** Leaves dedicated source search and returns to the global search results underneath. */
    fun closeDedicatedSearch() {
        if (state.value.dedicated == null) return
        closeFilters()
        dedicatedRun++
        mutableState.update { it.copy(dedicated = null) }
    }

    /** Runs a text search inside the open dedicated source, keeping active filters or returning to Popular if empty. */
    fun searchDedicated(text: String) {
        val dedicated = state.value.dedicated ?: return
        val source = source(dedicated.source.key) ?: return
        val query = text.trim()
        val applied = dedicated.kind as? BrowseKind.Filtered
        val nextKind = if (query.isEmpty() && (applied == null || applied.changed == 0)) {
            BrowseKind.Popular
        } else {
            BrowseKind.Filtered(query, applied?.filters.orEmpty(), applied?.changed ?: 0)
        }
        showDedicated(source, nextKind)
    }

    /** Shows the dedicated source's [kind] listing from the first page. */
    fun chooseDedicated(kind: BrowseKind) {
        showDedicated(dedicatedSource() ?: return, kind)
    }

    fun retryDedicated() {
        state.value.dedicated?.let { chooseDedicated(it.kind) }
    }

    /** Loads the dedicated listing's next page, unless one is loading or none is left. */
    fun dedicatedMore() {
        val dedicated = state.value.dedicated ?: return
        val loaded = dedicated.source.listing as? SeriesListing.Loaded ?: return
        val page = loaded.nextPage?.takeUnless { loaded.loadingMore } ?: return
        val source = source(dedicated.source.key) ?: return
        val kind = dedicated.kind
        val run = dedicated.run
        updateDedicated(run, loaded.copy(loadingMore = true, moreFailed = false))
        viewModelScope.launch {
            val next = loaded.withPage(page) { source.list(kind, it) }
            updateDedicated(run, next)
        }
    }

    /**
     * Closes the filter sheet and lists the active source's search for what it holds. Sent before the source's
     * filters have loaded, it keeps the filters the listing on screen applied and changes only the text.
     */
    fun searchSource() {
        val sheet = state.value.filterSheet ?: return
        val inDedicated = state.value.dedicated != null
        val applied = (state.value.dedicated?.kind ?: state.value.browseKind) as? BrowseKind.Filtered
        val query = sheet.text.trim()
        closeFilters()
        val nextKind = when {
            sheet.draft != null -> BrowseKind.Filtered(query, sheet.draft, sheet.draft.changedFrom(sheet.defaults.orEmpty()))
            else -> BrowseKind.Filtered(query, applied?.filters.orEmpty(), applied?.changed ?: 0)
        }
        if (inDedicated) chooseDedicated(nextKind) else choose(nextKind)
    }

    /**
     * Follows [shown], whether the tab shows, replacing what was followed before. Filter choices last only while the
     * tab shows: once it is left, the sheet closes and a filtered browse returns to the popular series. It is followed
     * here rather than in the tab's composition, which the pager drops before it reports another tab settled.
     */
    fun followShown(shown: Flow<Boolean>) {
        shownWatch?.cancel()
        shownWatch = viewModelScope.launch {
            shown.distinctUntilChanged().drop(1).collect { if (!it) dropFilters() }
        }
    }

    private fun dropFilters() {
        closeFilters()
        if (state.value.browseKind is BrowseKind.Filtered) choose(BrowseKind.Popular)
    }

    /**
     * Opens the filter sheet at what the active listing on screen chose. The source's filters load afresh each
     * time, since some sources fill theirs in from what they fetched meanwhile; one whose filters fail to load offers
     * none, so the sheet still searches it.
     */
    fun openFilters() {
        val dedicated = state.value.dedicated
        val source = (if (dedicated != null) source(dedicated.source.key) else browsedSource()) ?: return
        val applied = (dedicated?.kind ?: state.value.browseKind) as? BrowseKind.Filtered
        filterLoad?.cancel()
        mutableState.update { it.copy(filterSheet = FilterSheetState(text = applied?.text.orEmpty())) }
        val load = viewModelScope.launch {
            val defaults = try {
                source.filters()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            ensureActive()
            mutableState.update { state ->
                state.copy(filterSheet = state.filterSheet?.copy(defaults = defaults, draft = defaults.withChoices(applied?.filters.orEmpty())))
            }
        }
        filterLoad = load
    }

    /** Changes what the open filter sheet holds. */
    fun editFilters(edit: (FilterSheetState) -> FilterSheetState) {
        mutableState.update { it.copy(filterSheet = it.filterSheet?.let(edit)) }
    }

    /** Closes the filter sheet; its choices are dropped. */
    fun closeFilters() {
        filterLoad?.cancel()
        filterLoad = null
        mutableState.update { it.copy(filterSheet = null) }
    }

    /** Loads the browsed listing's next page, unless one is loading or none is left. */
    fun browseMore() {
        val browse = state.value.browse ?: return
        val loaded = browse.listing as? SeriesListing.Loaded ?: return
        val page = loaded.nextPage?.takeUnless { loaded.loadingMore } ?: return
        val source = source(browse.key) ?: return
        val kind = state.value.browseKind
        val run = state.value.browseRun
        updateBrowse(run, loaded.copy(loadingMore = true, moreFailed = false))
        viewModelScope.launch {
            val next = loaded.withPage(page) { source.list(kind, it) }
            updateBrowse(run, next)
        }
    }

    /** Loads [key]'s next page of search results, unless one is loading or none is left. */
    fun searchMore(key: SourceKey) {
        val query = searchText ?: return
        val loaded = state.value.search?.firstOrNull { it.key == key }?.listing as? SeriesListing.Loaded ?: return
        val page = loaded.nextPage?.takeUnless { loaded.loadingMore } ?: return
        val source = source(key) ?: return
        val run = searchRun
        updateSearch(key, run, loaded.copy(loadingMore = true, moreFailed = false))
        viewModelScope.launch {
            val next = loaded.withPage(page) { source.search(query, it) }
            if (source(key) === source) updateSearch(key, run, next)
        }
    }

    /** Searches every source for [text] at once; blank text ends the search. */
    fun search(text: String) {
        val query = text.trim().ifEmpty { return clearSearch() }
        val sources = state.value.sources.orEmpty()
        searchText = query
        searchRun++
        saved[SAVED_SEARCH] = query
        if (state.value.dedicated != null) closeFilters()
        mutableState.update {
            it.copy(
                search = sources.map { source -> source.toListing(sources, SeriesListing.Loading) },
                searchQuery = query,
                dedicated = null,
            )
        }
        sources.forEach { searchIn(it, query) }
    }

    fun retrySearch(key: SourceKey) {
        val query = searchText ?: return
        val source = source(key) ?: return
        updateSearch(key, searchRun, SeriesListing.Loading)
        searchIn(source, query)
    }

    fun clearSearch() {
        searchText = null
        searchRun++
        saved.remove<String>(SAVED_SEARCH)
        if (state.value.dedicated != null) closeFilters()
        mutableState.update { it.copy(search = null, searchQuery = "", dedicated = null) }
    }

    /** Stores [series], so its page has something to show at once, then reports it stored. */
    fun open(series: Series, opened: (SeriesId) -> Unit) {
        viewModelScope.launch {
            repository.store(series)
            opened(series.id)
        }
    }

    private fun searchIn(source: SeriesSource, query: String) {
        val run = searchRun
        viewModelScope.launch {
            val listing = firstPage { source.search(query, 1) }
            // An answer from a source that was switched off or replaced meanwhile is dropped.
            if (source(source.key) === source) updateSearch(source.key, run, listing)
        }
    }

    private fun updateSearch(key: SourceKey, run: Int, listing: SeriesListing) {
        if (searchRun != run) return
        mutableState.update { state ->
            state.copy(search = state.search?.map { if (it.key == key) it.copy(listing = listing) else it })
        }
    }

    private fun show(source: SeriesSource, kind: BrowseKind) {
        mutableState.update {
            it.copy(browse = source.toListing(it.sources.orEmpty(), SeriesListing.Loading), browseKind = kind, browseRun = it.browseRun + 1)
        }
        val run = state.value.browseRun
        viewModelScope.launch {
            val listing = firstPage { source.list(kind, 1) }
            updateBrowse(run, listing)
        }
    }

    private fun updateBrowse(run: Int, listing: SeriesListing) {
        if (state.value.browseRun != run) return
        mutableState.update { state -> state.copy(browse = state.browse?.copy(listing = listing)) }
    }

    private fun showDedicated(source: SeriesSource, kind: BrowseKind) {
        dedicatedRun++
        val run = dedicatedRun
        mutableState.update {
            it.copy(dedicated = DedicatedSourceState(source.toListing(it.sources.orEmpty(), SeriesListing.Loading), kind, run))
        }
        viewModelScope.launch {
            val listing = firstPage { source.list(kind, 1) }
            updateDedicated(run, listing)
        }
    }

    private fun updateDedicated(run: Int, listing: SeriesListing) {
        if (state.value.dedicated?.run != run) return
        mutableState.update { state ->
            state.copy(dedicated = state.dedicated?.let { it.copy(source = it.source.copy(listing = listing)) })
        }
    }

    private fun source(key: SourceKey) = state.value.sources?.firstOrNull { it.key == key }

    private fun browsedSource() = state.value.browse?.key?.let(::source)

    private fun dedicatedSource() = state.value.dedicated?.source?.key?.let(::source)
}

private fun SeriesSource.toListing(sources: List<SeriesSource>, listing: SeriesListing) =
    SeriesSourceListing(key, label(sources), listing, sourceName = name, language = language)

private suspend fun SeriesSource.list(kind: BrowseKind, page: Int): SeriesPage = when (kind) {
    BrowseKind.Popular -> popular(page)
    BrowseKind.Latest -> latest(page)
    is BrowseKind.Filtered -> search(kind.text, page, kind.filters)
}

/** A source's first page as a listing; any failure but cancellation becomes [SeriesListing.Failed]. Also the Switch source sheet's search. */
internal suspend fun firstPage(load: suspend () -> SeriesPage): SeriesListing = try {
    load().let { SeriesListing.Loaded(it.series, nextPage = 2.takeIf { _ -> it.hasNextPage }) }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    SeriesListing.Failed(failureLine(error))
}

/** [this] with page [page] from [load] added; a failure keeps what is loaded and holds that page back until Retry. */
private suspend fun SeriesListing.Loaded.withPage(page: Int, load: suspend (Int) -> SeriesPage): SeriesListing.Loaded = try {
    val more = load(page)
    copy(
        series = (series + more.series).distinctBy(Series::id),
        nextPage = (page + 1).takeIf { more.hasNextPage },
        loadingMore = false,
        moreFailed = false,
    )
} catch (error: CancellationException) {
    throw error
} catch (ignored: Exception) {
    // The loaded series stay on screen; the row's end offers the page again.
    copy(loadingMore = false, moreFailed = true)
}

/** What the screen says about [error]: a line for its reason, never the exception's own text. */
internal fun failureLine(error: Exception): String = sourceFailureDetail((error as? SourceAdapterException)?.reason)

private const val SAVED_SEARCH = "search"
private const val SAVED_BROWSE = "browse"

internal const val LOAD_FAILED = "Couldn't load"
