package com.theoriacodex.app.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.ui.sourceFailureDetail
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Inclusion
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesFilter
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesPage
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.UnsupportedEntriesException
import com.theoriacodex.domain.series.changedFrom
import com.theoriacodex.domain.series.seriesKind
import com.theoriacodex.domain.series.withChoices
import com.theoriacodex.domain.series.languageName
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
 * the screen can tell a new one from the last.
 */
data class DedicatedSourceState(
    val source: SeriesSourceListing,
    val kind: BrowseKind,
    val run: Int = 0,
)

/**
 * The Series tab. [sources] is null until the extensions that are on have loaded; [browse] is the chosen source's
 * [browseKind] listing, and [browseRun] counts the listings browsed, so the screen can tell a new one from the last;
 * [filterSheet] is the filter sheet while it is open; [search] is one listing per source while a search shows, and
 * null otherwise; a search sent before the extensions have loaded is empty until they have, and then runs.
 * [dedicated] is a single source's search opened from [search], keeping [search] intact underneath until Back.
 * [refreshing] is true while a refresh by hand checks the followed series for new entries, and [failedCheck] is the
 * last such check while some of its series failed, until the next starts. [kind] is the kind filter's
 * choice, null for both; [SeriesTabContent] says when it applies. [view] is null until the followed series are first
 * read, then Following when there are any; after that it changes only when chosen. [library] orders and narrows the
 * Following grid once [libraryLoaded] says the saved choice has arrived, and [followingQuery] narrows it to titles
 * holding it.
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
    val failedCheck: FollowedCheck? = null,
    val kind: SeriesKind? = null,
    val view: SeriesView? = null,
    val library: LibraryOptions = LibraryOptions(),
    val libraryLoaded: Boolean = false,
    val followingQuery: String = "",
)

/**
 * What the tab shows of its state and the followed series. [kinds] are the kinds of series the user has, among the
 * sources that are on and the followed series. The kind filter is offered only while there are both, and only then
 * narrows the Following grid and the search results to its [filter]; Browse lists every source, grouped by kind. In a
 * mixed list anime are [marked] apart from manga; under one kind the marks would say nothing.
 *
 * [following] is the Following grid: the followed series of [filter] that [library] and the title query show, in
 * [library]'s order; null until the followed series have been read. [followsAny] says whether anything is followed at
 * all, and [followedIds] holds every followed series, which Browse and search mark. [words] name entries in the
 * Following controls: a tab of only anime, or narrowed to anime, speaks of episodes.
 */
data class SeriesTabContent(
    val kinds: Set<SeriesKind>,
    val filter: SeriesKind?,
    val view: SeriesView,
    val following: List<FollowedSeries>?,
    val followsAny: Boolean,
    val followedIds: Set<SeriesId>,
    val library: LibraryOptions,
    val followingQuery: String,
    val sources: List<SeriesSource>?,
    val browse: SeriesSourceListing?,
    val search: List<SeriesSourceListing>?,
    val missingExtensions: Int = 0,
) {
    val offersFilter: Boolean get() = kinds.size > 1
    val marked: Boolean get() = offersFilter && filter == null
    val words: EntryWords get() = (filter ?: kinds.singleOrNull() ?: SeriesKind.MANGA).words
}

/** [followed] is null until the followed series have been read. */
internal fun SeriesTabState.content(followed: List<FollowedSeries>?): SeriesTabContent {
    val all = followed.orEmpty()
    val kinds = (sources.orEmpty().map { it.key.seriesKind } + all.map { it.series.id.source.seriesKind }).toSet()
    val filter = kind.takeIf { kinds.size > 1 }
    return SeriesTabContent(
        kinds = kinds,
        filter = filter,
        view = view ?: SeriesView.FOLLOWING,
        following = followed?.filter { filter.admits(it.series.id.source) }?.arranged(library, followingQuery),
        followsAny = all.isNotEmpty(),
        followedIds = all.mapTo(HashSet()) { it.series.id },
        library = library,
        followingQuery = followingQuery,
        sources = sources,
        browse = browse,
        search = search?.filter { filter.admits(it.key) },
        missingExtensions = sources?.let { on ->
            val keys = on.mapTo(HashSet()) { it.key }
            all.count { it.series.id.source !in keys }
        } ?: 0,
    )
}

/**
 * [sourcesChanged] emits once at the start and again whenever [loadSources] may answer differently. [saved] keeps the
 * search on screen and the browsed source across process death; both show again once the sources have loaded. The
 * browsed listing, its filters, dedicated search, the filter sheet, the kind filter and the view are kept only in
 * memory: after process death they start again at the source's popular series of both kinds. [library] keeps the
 * Following grid's sort and filter across launches.
 */
class SeriesTabViewModel(
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val library: SeriesLibraryPreferences = NoOpSeriesLibraryPreferences,
    /** Stores the followed series' new entries, which changes the shelf's unread counts, and announces them. */
    private val checkFollowed: suspend () -> FollowedCheck,
) : ViewModel() {
    private var searchText: String? = saved[SAVED_SEARCH]

    /** A genre search requested before sources finished loading. */
    private var pendingGenre: Pair<SourceKey, String>? = null

    /** The filter sheet's load of the source's filters; a sheet closed or opened again cancels it. */
    private var filterLoad: Job? = null

    /** Follows whether the tab shows; see [followShown]. */
    private var shownWatch: Job? = null
    private val mutableState = MutableStateFlow(
        SeriesTabState(
            search = searchText?.let { emptyList() },
            searchQuery = searchText.orEmpty(),
            // Kept across process death with the search, which shows only on Browse.
            view = saved.get<String>(SAVED_VIEW)?.let { name -> SeriesView.entries.firstOrNull { it.name == name } },
        ),
    )
    val state: StateFlow<SeriesTabState> = mutableState

    private val browseListing = PagedListing(viewModelScope, { state.value.browse?.listing }) { listing ->
        mutableState.update { it.copy(browse = it.browse?.copy(listing = listing)) }
    }
    private val dedicatedListing = PagedListing(viewModelScope, { state.value.dedicated?.source?.listing }) { listing ->
        mutableState.update { state -> state.copy(dedicated = state.dedicated?.let { it.copy(source = it.source.copy(listing = listing)) }) }
    }
    private val searchListings = mutableMapOf<SourceKey, PagedListing>()

    /**
     * What the screen shows: [state] with the followed series, under the kind filter while it applies. The rest shows
     * before the followed series have been read.
     */
    val content: StateFlow<SeriesTabContent> = combine(
        mutableState,
        repository.observeFollowed().map<List<FollowedSeries>, List<FollowedSeries>?> { it }.onStart { emit(null) },
    ) { state, followed ->
        // Held back until the saved sort and filter have arrived, so the grid never shows under the defaults first.
        state.content(followed.takeIf { state.libraryLoaded })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value.content(null))

    init {
        viewModelScope.launch { sourcesChanged.collectLatest { showSources(loadSources()) } }
        viewModelScope.launch {
            mutableState
                .map { state -> state.sources?.takeIf { state.view == SeriesView.BROWSE && state.search == null && state.browse == null } }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { sources ->
                    val browsed = saved.get<String>(SAVED_BROWSE)
                    (sources.firstOrNull { it.key.id == browsed } ?: sources.firstOrNull())?.let { browse(it.key) }
                }
        }
        // Nothing shows until the view is chosen, so the saved sort and filter arrive with it.
        viewModelScope.launch {
            val options = library.read()
            val following = repository.observeHasFollowed().first()
            val opening = state.value.view ?: if (following) SeriesView.FOLLOWING else SeriesView.BROWSE
            saved[SAVED_VIEW] = opening.name
            mutableState.update { it.copy(view = it.view ?: opening, library = options, libraryLoaded = true) }
        }
    }

    fun chooseView(view: SeriesView) {
        saved[SAVED_VIEW] = view.name
        mutableState.update { it.copy(view = view) }
    }

    /** Orders and narrows the Following grid by [options], and remembers them. */
    fun chooseLibrary(options: LibraryOptions) {
        mutableState.update { it.copy(library = options) }
        library.write(options)
    }

    /** Clears every filter on the Following grid: kind, Sort & filter's filter and the title query. */
    fun showAllFollowing() {
        chooseLibrary(state.value.library.copy(show = LibraryShow.ALL))
        mutableState.update { it.copy(kind = null, followingQuery = "") }
    }

    /** Narrows the Following grid to titles holding [query], as it is typed. */
    fun filterFollowing(query: String) {
        mutableState.update { it.copy(followingQuery = query) }
    }

    /** Takes the Following title query to Browse and searches every source, of both kinds, for it. */
    fun searchAllSources() {
        val query = state.value.followingQuery
        mutableState.update { it.copy(followingQuery = "", kind = null) }
        // Searched first, so Browse never loads the listing the results hide.
        search(query)
        chooseView(SeriesView.BROWSE)
    }

    /** Checks the followed series for new entries, unless a check is already running. */
    fun refresh() {
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, failedCheck = null) }
        viewModelScope.launch {
            var check: FollowedCheck? = null
            try {
                check = checkFollowed()
            } finally {
                mutableState.update { it.copy(refreshing = false, failedCheck = check?.takeIf { it.failed > 0 }) }
            }
        }
    }

    /**
     * Keeps what is on screen for the sources still on offer, and while Browse shows, browses the first when the browsed
     * one is gone. A browsed source that was replaced (its extension updated) is browsed afresh, as if chosen again. A
     * search on screen, or one sent while the extensions were loading, runs in every source that is new or replaced.
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
                    sources.firstOrNull { it.key == browse.key }?.takeIf { it !in fresh }?.toListing(sources, browse.listing)
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
        (searchListings.keys - sources.mapTo(HashSet()) { it.key }).forEach { searchListings.remove(it)?.cancel() }
        if (hadDedicated && state.value.dedicated == null) closeDedicated()
        val currentDedicated = state.value.dedicated
        if (currentDedicated != null && fresh.any { it.key == currentDedicated.source.key }) {
            closeFilters()
            source(currentDedicated.source.key)?.let { showDedicated(it, currentDedicated.kind) }
        }
        if (query != null) fresh.forEach { searchIn(it, query) }
        pendingGenre?.let { (genreSource, genre) ->
            pendingGenre = null
            openGenreSearch(genreSource, genre)
        }
    }

    /** Shows [key]'s popular series from the first page; the filters chosen before are dropped. */
    fun browse(key: SourceKey) {
        val source = source(key) ?: return
        saved[SAVED_BROWSE] = key.id
        closeFilters()
        showBrowse(source, BrowseKind.Popular)
    }

    /** Narrows the Following grid and search results to [kind], or shows both with null. */
    fun chooseKind(kind: SeriesKind?) {
        mutableState.update { it.copy(kind = kind) }
    }

    /** Shows the browsed source's [kind] listing from the first page. */
    fun choose(kind: BrowseKind) {
        showBrowse(browsedSource() ?: return, kind)
    }

    fun retryBrowse() = choose(state.value.browseKind)

    /**
     * Opens [key]'s dedicated search seeded with the active global search query. Reuses results already loaded by
     * global search for [key] when available so the transition is immediate; otherwise fetches page 1.
     */
    fun openDedicatedSearch(key: SourceKey) {
        val source = source(key) ?: return
        val query = searchText.orEmpty().trim()
        closeFilters()
        val kind = if (query.isNotEmpty()) BrowseKind.Filtered(query, emptyList(), changed = 0) else BrowseKind.Popular
        val existing = state.value.search?.firstOrNull { it.key == key }?.listing as? SeriesListing.Loaded
        showDedicated(source, kind, existing?.takeIf { query.isNotEmpty() }?.copy(loadingMore = false, moreFailed = false))
    }

    /**
     * Opens [key]'s dedicated search filtered by [genre] when the source exposes a matching filter option, falling
     * back to a source text search for [genre] when it does not.
     */
    fun openGenreSearch(key: SourceKey, genre: String) {
        val needle = genre.trim()
        if (needle.isEmpty()) return
        if (state.value.sources == null) {
            pendingGenre = key to needle
            return
        }
        val source = source(key) ?: return
        closeFilters()
        mutableState.update {
            it.copy(dedicated = DedicatedSourceState(source.toListing(it.sources.orEmpty(), SeriesListing.Loading), BrowseKind.Popular, it.nextDedicatedRun))
        }
        dedicatedListing.launch {
            val defaults = runCatchingExceptionsPreservingCancellation { source.filters() }.getOrElse { emptyList() }
            ensureActive()
            val matched = defaults.selectingGenre(needle)
            val kind = if (matched != null) {
                BrowseKind.Filtered("", matched, matched.changedFrom(defaults))
            } else {
                BrowseKind.Filtered(needle, emptyList(), changed = 0)
            }
            showDedicated(source, kind)
        }
    }

    /** Leaves dedicated source search and returns to the global search results underneath. */
    fun closeDedicatedSearch() {
        if (state.value.dedicated == null) return
        closeDedicated()
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
    fun dedicatedMore() = dedicatedListing.more()

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
            val defaults = runCatchingExceptionsPreservingCancellation {
                source.filters()
            }.getOrElse {
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
    fun browseMore() = browseListing.more()

    /** Loads [key]'s next page of search results, unless one is loading or none is left. */
    fun searchMore(key: SourceKey) {
        searchListings[key]?.more()
    }

    /** Searches every source for [text] at once; blank text ends the search. */
    fun search(text: String) {
        val query = text.trim().ifEmpty { return clearSearch() }
        val sources = state.value.sources.orEmpty()
        searchText = query
        saved[SAVED_SEARCH] = query
        if (state.value.dedicated != null) closeDedicated()
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
        showSearch(key, SeriesListing.Loading)
        searchIn(source, query)
    }

    fun clearSearch() {
        searchText = null
        searchListings.values.forEach(PagedListing::cancel)
        saved.remove<String>(SAVED_SEARCH)
        if (state.value.dedicated != null) closeDedicated()
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
        val key = source.key
        val listing = searchListings.getOrPut(key) {
            PagedListing(viewModelScope, { state.value.search?.firstOrNull { it.key == key }?.listing }) { showSearch(key, it) }
        }
        listing.start { source.search(query, it) }
    }

    private fun closeDedicated() {
        closeFilters()
        dedicatedListing.cancel()
    }

    private fun showSearch(key: SourceKey, listing: SeriesListing) {
        mutableState.update { state ->
            state.copy(search = state.search?.map { if (it.key == key) it.copy(listing = listing) else it })
        }
    }

    private fun showBrowse(source: SeriesSource, kind: BrowseKind) {
        mutableState.update {
            it.copy(browse = source.toListing(it.sources.orEmpty(), SeriesListing.Loading), browseKind = kind, browseRun = it.browseRun + 1)
        }
        browseListing.start { source.list(kind, it) }
    }

    private fun showDedicated(source: SeriesSource, kind: BrowseKind, seeded: SeriesListing.Loaded? = null) {
        mutableState.update {
            it.copy(dedicated = DedicatedSourceState(source.toListing(it.sources.orEmpty(), seeded ?: SeriesListing.Loading), kind, it.nextDedicatedRun))
        }
        val pages: suspend (Int) -> SeriesPage = { source.list(kind, it) }
        if (seeded == null) dedicatedListing.start(pages) else dedicatedListing.resume(pages)
    }

    private fun source(key: SourceKey) = state.value.sources?.firstOrNull { it.key == key }

    private fun browsedSource() = state.value.browse?.key?.let(::source)

    private fun dedicatedSource() = state.value.dedicated?.source?.key?.let(::source)
}

/** Returns [this] with the first filter option matching [genre] selected/included, or null when no filter matches. */
internal fun List<SeriesFilter>.selectingGenre(genre: String): List<SeriesFilter>? {
    val needle = genre.trim()
    if (needle.isEmpty()) return null
    var matched = false
    fun apply(filters: List<SeriesFilter>): List<SeriesFilter> = filters.map { filter ->
        if (matched) filter else matchGenreFilter(filter, needle, ::apply)?.also { matched = true } ?: filter
    }
    return apply(this).takeIf { matched }
}

private fun matchGenreFilter(
    filter: SeriesFilter,
    needle: String,
    recurse: (List<SeriesFilter>) -> List<SeriesFilter>,
): SeriesFilter? = when (filter) {
    is SeriesFilter.Check -> filter.takeIf { it.name.equals(needle, ignoreCase = true) }?.copy(checked = true)
    is SeriesFilter.TriState -> filter.takeIf { it.name.equals(needle, ignoreCase = true) }?.copy(state = Inclusion.INCLUDE)
    is SeriesFilter.Select -> filter.options.indexOfFirst { it.equals(needle, ignoreCase = true) }
        .takeIf { it >= 0 }
        ?.let { filter.copy(selected = it) }
    is SeriesFilter.Group -> recurse(filter.filters).takeIf { it != filter.filters }?.let { filter.copy(filters = it) }
    else -> null
}

private fun SeriesSource.toListing(sources: List<SeriesSource>, listing: SeriesListing) =
    SeriesSourceListing(key, label(sources), listing, sourceName = name, language = language)

private val SeriesTabState.nextDedicatedRun: Int get() = (dedicated?.run ?: 0) + 1

private suspend fun SeriesSource.list(kind: BrowseKind, page: Int): SeriesPage = when (kind) {
    BrowseKind.Popular -> popular(page)
    BrowseKind.Latest -> latest(page)
    is BrowseKind.Filtered -> search(kind.text, page, kind.filters)
}

/**
 * What the screen says about [error]: a line for its reason, never the exception's own text, except for an
 * [UnsupportedEntriesException], whose text is written for the user.
 */
internal fun failureLine(error: Throwable): String = when (error) {
    is UnsupportedEntriesException -> error.message
    else -> sourceFailureDetail((error as? SourceAdapterException)?.reason)
}

private const val SAVED_SEARCH = "search"
private const val SAVED_BROWSE = "browse"
private const val SAVED_VIEW = "view"

internal const val LOAD_FAILED = "Couldn't load"
