package com.theoriacodex.app.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.ui.sourceFailureDetail
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

sealed interface SeriesListing {
    data object Loading : SeriesListing

    data class Failed(val message: String) : SeriesListing

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

internal val SeriesSourceListing.languageSubtitle: String?
    get() = language.takeIf(String::isNotBlank)?.let(::languageName)

sealed interface BrowseKind {
    data object Popular : BrowseKind

    data object Latest : BrowseKind

    data class Filtered(val text: String, val filters: List<SeriesFilter>, val changed: Int) : BrowseKind {
        val active: Int get() = changed + if (text.isEmpty()) 0 else 1
    }
}

data class FilterSheetState(
    val text: String,
    val defaults: List<SeriesFilter>? = null,
    val draft: List<SeriesFilter>? = null,
    val expanded: Set<String> = emptySet(),
)

data class DedicatedSourceState(
    val source: SeriesSourceListing,
    val kind: BrowseKind,
    val run: Int = 0,
)

data class SeriesTabState(
    val sources: List<SeriesSource>? = null,
    val browse: SeriesSourceListing? = null,
    val browseKind: BrowseKind = BrowseKind.Popular,
    val browseRun: Int = 0,
    val filterSheet: FilterSheetState? = null,
    val search: List<SeriesSourceListing>? = null,
    val searchQuery: String = "",
    val dedicated: DedicatedSourceState? = null,
    val checking: CheckProgress? = null,
    val found: List<SeriesUpdate> = emptyList(),
    val failedCheck: FollowedCheck? = null,
    val checkRun: Int = 0,
    val kind: SeriesKind? = null,
    val view: SeriesView? = null,
    val library: LibraryOptions = LibraryOptions(),
    val libraryLoaded: Boolean = false,
    val followingQuery: String = "",
)

data class SeriesTabContent(
    val kinds: Set<SeriesKind>,
    val filter: SeriesKind?,
    val view: SeriesView,
    val following: List<FollowedSeries>?,
    val followsAny: Boolean,
    val followed: Map<SeriesId, FollowedSeries>,
    val library: LibraryOptions,
    val followingQuery: String,
    val sources: List<SeriesSource>?,
    val browse: SeriesSourceListing?,
    val search: List<SeriesSourceListing>?,
) {
    val followedIds: Set<SeriesId> get() = followed.keys
    val offersFilter: Boolean get() = kinds.size > 1
    val marked: Boolean get() = offersFilter && filter == null
    val words: EntryWords get() = (filter ?: kinds.singleOrNull() ?: SeriesKind.MANGA).words
    val checkWords: EntryWords? get() = followedIds.mapTo(HashSet()) { it.source.seriesKind }.singleOrNull()?.words
}

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
        followed = all.associateBy { it.series.id },
        library = library,
        followingQuery = followingQuery,
        sources = sources,
        browse = browse,
        search = search?.filter { filter.admits(it.key) },
    )
}

class SeriesTabViewModel(
    private val repository: SeriesRepository,
    private val loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*> = flowOf(Unit),
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val library: SeriesLibraryPreferences = NoOpSeriesLibraryPreferences,
    private val download: (suspend (SeriesId, DownloadChoice) -> Unit)? = null,
    private val checkFollowed: suspend (only: Set<SeriesId>?, onProgress: (CheckProgress) -> Unit) -> FollowedCheck,
) : ViewModel() {
    private var foundShown: Job? = null

    private var searchText: String? = saved[SAVED_SEARCH]

    private var pendingGenre: Pair<SourceKey, String>? = null

    private var filterLoad: Job? = null

    private var shownWatch: Job? = null
    private val mutableState = MutableStateFlow(
        SeriesTabState(
            search = searchText?.let { emptyList() },
            searchQuery = searchText.orEmpty(),
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

    val content: StateFlow<SeriesTabContent> = combine(
        mutableState,
        repository.observeFollowed().map<List<FollowedSeries>, List<FollowedSeries>?> { it }.onStart { emit(null) },
    ) { state, followed ->
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

    fun chooseLibrary(options: LibraryOptions) {
        mutableState.update { it.copy(library = options) }
        library.write(options)
    }

    fun showAllFollowing() {
        chooseLibrary(state.value.library.copy(show = LibraryShow.ALL))
        mutableState.update { it.copy(kind = null, followingQuery = "") }
    }

    fun filterFollowing(query: String) {
        mutableState.update { it.copy(followingQuery = query) }
    }

    fun searchAllSources() {
        val query = state.value.followingQuery
        mutableState.update { it.copy(followingQuery = "", kind = null) }
        search(query)
        chooseView(SeriesView.BROWSE)
    }

    fun refresh() = check(only = null)

    fun retry(series: Set<SeriesId>) = check(only = series)

    fun dismissFailures() {
        mutableState.update { it.copy(failedCheck = null) }
    }

    fun canDownload(item: FollowedSeries): Boolean {
        val id = item.series.id
        return download != null && id.source.seriesKind == SeriesKind.MANGA && state.value.sources.orEmpty().any { it.key == id.source }
    }

    fun download(id: SeriesId, choice: DownloadChoice) {
        val download = download ?: return
        viewModelScope.changeDownloads { download(id, choice) }
    }

    fun markAllRead(id: SeriesId, read: Boolean, onUndo: (BulkReadUndo) -> Unit) {
        viewModelScope.launch { repository.markAllRead(id, read)?.let(onUndo) }
    }

    fun undoBulkRead(id: SeriesId, undo: BulkReadUndo) {
        viewModelScope.launch { repository.restoreProgress(id, undo.restore) }
    }

    fun unfollow(id: SeriesId) {
        viewModelScope.launch { repository.setFollowed(id, false) }
    }

    private fun check(only: Set<SeriesId>?) {
        if (state.value.checking != null) return
        foundShown?.cancel()
        mutableState.update { it.copy(checking = CheckProgress(0, 0), found = emptyList(), failedCheck = it.failedCheck.takeIf { only != null }) }
        foundShown = viewModelScope.launch {
            var check: FollowedCheck? = null
            try {
                check = checkFollowed(only) { progress -> mutableState.update { it.copy(checking = progress) } }
            } finally {
                mutableState.update { state ->
                    val failed = check?.let { done -> state.failedCheck?.retried(only.orEmpty(), done) ?: done } ?: state.failedCheck
                    state.copy(checking = null, found = check?.updates.orEmpty(), failedCheck = failed?.takeIf { it.failed > 0 }, checkRun = state.checkRun + if (check?.failures.isNullOrEmpty()) 0 else 1)
                }
            }
            if (check.updates.isNotEmpty()) {
                delay(FOUND_SHOWN)
                mutableState.update { it.copy(found = emptyList()) }
            }
        }
    }

    private fun showSources(sources: List<SeriesSource>) {
        val before = state.value.sources.orEmpty()
        val fresh = sources.filter { source -> before.none { it === source } }
        val query = searchText
        val hadDedicated = state.value.dedicated != null
        mutableState.update { state ->
            state.copy(
                sources = sources,
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
        if (state.value.browse == null) browseListing.cancel()
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

    fun browse(key: SourceKey) {
        val source = source(key) ?: return
        saved[SAVED_BROWSE] = key.id
        closeFilters()
        showBrowse(source, BrowseKind.Popular)
    }

    fun chooseKind(kind: SeriesKind?) {
        mutableState.update { it.copy(kind = kind) }
    }

    fun choose(kind: BrowseKind) {
        showBrowse(browsedSource() ?: return, kind)
    }

    fun retryBrowse() = choose(state.value.browseKind)

    fun openDedicatedSearch(key: SourceKey) {
        val source = source(key) ?: return
        val query = searchText.orEmpty().trim()
        closeFilters()
        val kind = if (query.isNotEmpty()) BrowseKind.Filtered(query, emptyList(), changed = 0) else BrowseKind.Popular
        val existing = state.value.search?.firstOrNull { it.key == key }?.listing as? SeriesListing.Loaded
        showDedicated(source, kind, existing?.takeIf { query.isNotEmpty() }?.copy(loadingMore = false, moreFailed = false))
    }

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

    fun closeDedicatedSearch() {
        if (state.value.dedicated == null) return
        closeDedicated()
        mutableState.update { it.copy(dedicated = null) }
    }

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

    fun chooseDedicated(kind: BrowseKind) {
        showDedicated(dedicatedSource() ?: return, kind)
    }

    fun retryDedicated() {
        state.value.dedicated?.let { chooseDedicated(it.kind) }
    }

    fun dedicatedMore() = dedicatedListing.more()

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

    fun editFilters(edit: (FilterSheetState) -> FilterSheetState) {
        mutableState.update { it.copy(filterSheet = it.filterSheet?.let(edit)) }
    }

    fun closeFilters() {
        filterLoad?.cancel()
        filterLoad = null
        mutableState.update { it.copy(filterSheet = null) }
    }

    fun browseMore() = browseListing.more()

    fun searchMore(key: SourceKey) {
        searchListings[key]?.more()
    }

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

internal fun failureLine(error: Throwable): String = when (error) {
    is UnsupportedEntriesException -> error.message
    else -> sourceFailureDetail((error as? SourceAdapterException)?.reason)
}

private fun FollowedCheck.retried(series: Set<SeriesId>, retry: FollowedCheck): FollowedCheck =
    copy(failures = failures.filter { it.series.id !in series } + retry.failures)

private const val SAVED_SEARCH = "search"
private val FOUND_SHOWN = 3.seconds
private const val SAVED_BROWSE = "browse"
private const val SAVED_VIEW = "view"

internal const val LOAD_FAILED = "Couldn't load"
