package com.theoriacodex.app.search

import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedScrollSignal
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (SearchAction) -> Unit,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    likedPostIds: Set<PostId> = emptySet(),
    pendingLikePostIds: Set<PostId> = emptySet(),
    savedPostIds: Set<PostId> = emptySet(),
    watchedPostIds: Set<PostId> = emptySet(),
    favoriteTags: Map<SourceKey, List<String>> = emptyMap(),
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    onDurationFilterChanged: (Boolean) -> Unit = {},
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenSourceAccounts: () -> Unit = {},
    onAddFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
    onRemoveFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
    oldSearchBar: Boolean = false,
    savedSearches: List<SavedSearchEntry> = emptyList(),
    onOpenSavedSearch: (SavedSearchEntry) -> Unit = {},
) {
    val composerOpen = rememberSaveable { mutableStateOf(false) }
    val chrome = remember(composerOpen) { SearchChromeState(composerOpen) }
    val actionPost = remember { SearchActionPostState() }
    val inputFocus = rememberSearchInputFocus()
    val scope = rememberCoroutineScope()
    val gridState = rememberRetainedFeedGridState("search")
    ScrollToTopOnTabReselect(TopLevelDestination.Search) { gridState.animateScrollToItem(0) }
    val feed = rememberSearchFeed(state, fabRestoreState, likedPostIds, pendingLikePostIds, savedPostIds, watchedPostIds, durationStates, resolveUnknownAnimatedDurations)
    val serverFilters = state.query.serverFilters()
    val filterScroll = rememberFeedScrollSignal()
    var filterSheetBaseline by remember { mutableStateOf(serverFilters) }
    LaunchedEffect(feed.animatedDurationFilterActive) { onDurationFilterChanged(feed.animatedDurationFilterActive) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val pageDemand = rememberSearchScrollAndPaging(state, feed, gridState, resolveUnknownAnimatedDurations, onAction)
    SearchScrollDismissEffect(gridState, chrome, inputFocus)
    val applyDraft = { chrome.applyDraft(inputFocus, onAction) }
    val filterActions = SearchFilterActions(state, fabRestoreState, rememberUpdatedState(serverFilters), onAction, onFabRestoreStateChange, applyDraft)
    val sourcePicker: @Composable () -> Unit = { SearchScreenSourcePicker(state, chrome, inputFocus, onAction) }
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            SearchFilterFab(searchFilterLabels(serverFilters, feed, fabRestoreState), filterScroll, { filterActions.clearFilters(serverFilters) }) {
                inputFocus.clear()
                filterSheetBaseline = serverFilters
                chrome.showFilterSheet = true
            }
        }
    ) { padding ->
        SearchChromeLayout(
            padding, state, feed, fabRestoreState, chrome, oldSearchBar, sourcePicker, applyDraft, gridState, pageDemand, inputFocus,
            pixivUgoiraClient, likedPostIds, durationStateForPost, onToggleLike, onDurationPostVisibilityChanged,
            onAuthoritativeDurationKnown, resolvePostById, recoverPostMedia, onAction,
        ) { post ->
            inputFocus.clear()
            actionPost.open(post, state.content.results, scope, resolvePostById)
        }
    }
    actionPost.post?.let { post ->
        SearchPostSheet(
            post, actionPost, state, creatorBrowsingSources, tagVideoCountProvider, fetchTagVideoCounts, onAction, onSaveToDevice,
            onRequestSaveToCodex, onOpenCreatorProfile, onOpenLegacyCreatorProfile, onPostUrlCopied, onAddFavoriteTag, applyDraft,
        )
    }
    if (chrome.composerOpen) {
        SearchComposerDialog(
            state, feed, chrome, gridState, sourcePicker, applyDraft, onAction, onOpenSourceAccounts, savedSearches, onOpenSavedSearch,
        )
    }
    if (chrome.showFavoriteTagSheet) {
        SearchFavoriteTags(state, favoriteTags, tagVideoCountProvider, fetchTagVideoCounts, onAction, onRemoveFavoriteTag, onDismiss = { chrome.showFavoriteTagSheet = false })
    }
    if (chrome.showFilterSheet) {
        SearchFilterSheet(state, feed, fabRestoreState, onAction, onFabRestoreStateChange, filterActions::resetFilterDraft) {
            // Results change once, when the filters are settled, however many were adjusted.
            chrome.showFilterSheet = false
            if (serverFilters != filterSheetBaseline) applyDraft()
        }
    }
}

/** Scrolling results tucks the search bar away and gives the keyboard back. */
@Composable
private fun SearchScrollDismissEffect(gridState: LazyStaggeredGridState, chrome: SearchChromeState, inputFocus: SearchInputFocus) {
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                chrome.searchBarDismissedByScroll = true
                inputFocus.clear()
            }
        }
    }
}

@Composable
private fun SearchFilterFab(
    labels: List<String>,
    scrollSignal: FeedScrollSignal,
    onClear: () -> () -> Unit,
    onClick: () -> Unit,
) {
    FeedFilterFab(
        modifier = Modifier.padding(bottom = 8.dp),
        labels = labels,
        contentDescription = "Filter and sort",
        onClick = onClick,
        onClear = onClear,
        scrollSignal = scrollSignal,
    )
}

@Composable
private fun SearchChromeLayout(
    padding: PaddingValues,
    state: SearchUiState,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    chrome: SearchChromeState,
    oldSearchBar: Boolean,
    sourcePicker: @Composable () -> Unit,
    applyDraft: () -> Unit,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onToggleLike: ((Post) -> Unit)?,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    CollapsingSearchChrome(
        expanded = !chrome.searchBarDismissedByScroll,
        legacy = oldSearchBar,
        onExpand = { chrome.composerOpen = true },
        sourcePicker = sourcePicker,
        horizontalPadding = 16.dp,
        modifier = Modifier.then(inputFocus.rootModifier).fillMaxSize().padding(padding)
            .padding(top = if (oldSearchBar) 6.dp else 0.dp, bottom = 8.dp),
        inputField = { SearchChromeInput(state, feed, fabRestoreState, chrome, oldSearchBar, sourcePicker, applyDraft) },
    ) {
        val cards = rememberFeedCardEnvironment(
            pixivUgoiraClient, likedPostIds, feed.durationMetadata.knownDurationMsByPostId, durationStateForPost,
            onToggleLike, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown,
        )
        SearchResultsBody(state, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction, openPostActions)
    }
}

/** Which of Search's surfaces are open. The composer's state survives recreation; the sheets' does not. */
@Stable
private class SearchChromeState(composerOpen: MutableState<Boolean>) {
    var composerOpen by composerOpen
    var searchBarDismissedByScroll by mutableStateOf(false)
    var showFilterSheet by mutableStateOf(false)
    var showFavoriteTagSheet by mutableStateOf(false)

    fun closeComposer(onAction: (SearchAction) -> Unit) {
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }

    fun applyDraft(inputFocus: SearchInputFocus, onAction: (SearchAction) -> Unit) {
        inputFocus.clear(force = true)
        onAction(SearchAction.ApplyDraft)
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }
}

@Composable
private fun SearchComposerDialog(
    state: SearchUiState,
    feed: SearchFeed,
    chrome: SearchChromeState,
    gridState: LazyStaggeredGridState,
    sourcePicker: @Composable () -> Unit,
    applyDraft: () -> Unit,
    onAction: (SearchAction) -> Unit,
    onOpenSourceAccounts: () -> Unit,
    savedSearches: List<SavedSearchEntry>,
    onOpenSavedSearch: (SavedSearchEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resetScroll = { scope.launch { resetSearchScroll(feed.visibleResults, gridState, onAction) } }
    SearchComposer(
        state, onAction, sourcePicker, applyDraft,
        onReset = {
            onAction(SearchAction.ClearDraft)
            onAction(SearchAction.ClearAutocomplete)
            chrome.showFilterSheet = false
            resetScroll()
        },
        onUndo = {
            onAction(SearchAction.UndoSearch)
            onAction(SearchAction.ClearAutocomplete)
            chrome.showFilterSheet = false
        },
        onOpenFavorites = { chrome.showFavoriteTagSheet = true },
        onOpenSourceAccounts = onOpenSourceAccounts,
        onDigitsCommitted = { resetScroll() },
        onDismiss = { chrome.closeComposer(onAction) },
        savedSearches = savedSearches,
        onOpenSavedSearch = { entry ->
            chrome.closeComposer(onAction)
            onOpenSavedSearch(entry)
        },
    )
}

@Composable
private fun SearchScreenSourcePicker(
    state: SearchUiState,
    chrome: SearchChromeState,
    inputFocus: SearchInputFocus,
    onAction: (SearchAction) -> Unit,
) {
    SearchSourcePicker(
        draftSourceScope = state.query.draftSourceScope,
        options = state.query.modeOptions,
        unifiedSourceCount = state.query.unifiedSources.size,
        unifiedSources = state.query.unifiedSources,
        onOpen = { inputFocus.clear() },
        onModeSelected = { mode ->
            chrome.searchBarDismissedByScroll = false
            onAction(SearchAction.SelectMode(mode))
        },
        onTemporarySourceToggled = { source ->
            chrome.searchBarDismissedByScroll = false
            onAction(SearchAction.ToggleTemporarySource(source))
        },
    )
}

@Composable
private fun SearchChromeInput(
    state: SearchUiState,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    chrome: SearchChromeState,
    oldSearchBar: Boolean,
    sourcePicker: @Composable () -> Unit,
    applyDraft: () -> Unit,
) {
    SearchSummaryBar(
        summary = collapsedSearchContextSummary(state.query.draft, activeSearchFilterCount(state, feed, fabRestoreState)),
        validationMessage = state.query.validationMessage,
        showSearchAction = state.hasPendingChanges && !chrome.composerOpen,
        onOpen = { chrome.composerOpen = true },
        onSearch = applyDraft,
        trailing = { if (oldSearchBar) sourcePicker() },
    )
}

@Composable
private fun SearchPostSheet(
    post: Post,
    actionPost: SearchActionPostState,
    state: SearchUiState,
    creatorBrowsingSources: Set<SourceKey>,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAction: (SearchAction) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit,
    onAddFavoriteTag: (SourceKey, String) -> Unit,
    applyDraft: () -> Unit,
) {
    PostActionSheet(
        post = post,
        creatorBrowsingSources = creatorBrowsingSources,
        onDismiss = actionPost::dismiss,
        onSaveToDevice = { onSaveToDevice(post) },
        onSaveToCodex = { onRequestSaveToCodex(post) },
        saveToCodexQuery = state.query.applied,
        onOpenCreatorProfile = onOpenCreatorProfile,
        onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
        onPostUrlCopied = onPostUrlCopied,
        tagContent = {
            PostTagActionSection(
                draftQuery = state.query.draft,
                loading = actionPost.resolving && !post.hasActionableTags(),
                post = post,
                tagVideoCountProvider = tagVideoCountProvider,
                fetchTagVideoCounts = fetchTagVideoCounts,
                onAddIncludeTerm = { term ->
                    onAction(SearchAction.AddPostIncludeTerm(post, term))
                    true
                },
                onAddExcludeTerm = { term ->
                    onAction(SearchAction.AddPostExcludeTerm(post, term))
                    true
                },
                onRemoveIncludeTerm = { term -> onAction(SearchAction.RemoveIncludeTerm(term)) },
                onRemoveExcludeTerm = { term -> onAction(SearchAction.RemoveExcludeTerm(term)) },
                onFavoriteTagLongPress = onAddFavoriteTag,
                onSearch = {
                    actionPost.dismiss()
                    applyDraft()
                },
            )
        },
    )
}

/** The post whose action sheet is open, shown at once and replaced when fuller data arrives. */
@Stable
private class SearchActionPostState {
    var post by mutableStateOf<Post?>(null)
        private set
    var resolving by mutableStateOf(false)
        private set

    fun open(
        requested: Post,
        results: List<Post>,
        scope: CoroutineScope,
        resolvePostById: suspend (PostId) -> Post?,
    ) {
        val displayPost = results.firstOrNull { candidate -> candidate.id == requested.id } ?: requested
        post = displayPost
        resolving = false
        if (displayPost.hasActionableTags()) return
        resolving = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation { resolvePostById(requested.id) }.getOrNull()
            if (post?.id == requested.id) {
                post = resolved ?: displayPost
                resolving = false
            }
        }
    }

    fun dismiss() {
        post = null
        resolving = false
    }
}

/** Filter commands for one composition, so each sees the current restore state and draft. */
private class SearchFilterActions(
    private val state: SearchUiState,
    private val fabRestoreState: FeedFabRestoreState,
    private val currentServerFilters: State<SearchServerFilters>,
    private val onAction: (SearchAction) -> Unit,
    private val onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    private val applyDraft: () -> Unit,
) {
    private fun applyServerFilters(filters: SearchServerFilters) {
        onAction(SearchAction.SelectSort(filters.sort))
        onAction(SearchAction.SetDateRange(filters.dateRange))
        // These two rewrite the draft's nhentai terms, so only a real change may dispatch them.
        if (filters.fullColor != currentServerFilters.value.fullColor) onAction(SearchAction.SetNhentaiFullColor(filters.fullColor))
        if (filters.language != currentServerFilters.value.language) onAction(SearchAction.SetNhentaiLanguage(filters.language))
    }

    fun resetFilterDraft() {
        applyServerFilters(DEFAULT_SERVER_FILTERS)
        onFabRestoreStateChange(
            fabRestoreState.copy(hideLiked = false, hideSaved = false, hideWatched = false)
                .withSearchAnimationFilter(state.query.draft.mode) { restoreState ->
                    restoreState.copy(
                        animatedOnly = false,
                        durationMinBucket = AnimatedDurationRange.Full.normalizedMinBucket,
                        durationMaxBucket = AnimatedDurationRange.Full.normalizedMaxBucket,
                    )
                },
        )
    }

    /** Clears every filter and returns how to put them back. */
    fun clearFilters(previousServerFilters: SearchServerFilters): () -> Unit {
        val previousFab = fabRestoreState
        val serverFiltersActive = previousServerFilters != DEFAULT_SERVER_FILTERS
        resetFilterDraft()
        if (serverFiltersActive) applyDraft()
        return {
            onFabRestoreStateChange(previousFab)
            applyServerFilters(previousServerFilters)
            if (serverFiltersActive) applyDraft()
        }
    }
}

private fun searchFilterLabels(
    serverFilters: SearchServerFilters,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
): List<String> = listOfNotNull(
    sortModeLabel(serverFilters.sort).takeIf { serverFilters.sort != SortMode.NEWEST },
    serverFilters.dateRange?.let { dateRangeLabel(it, System.currentTimeMillis()) },
    "Animated".takeIf { feed.animatedFilterActive },
    animatedDurationRangeLabel(feed.animatedDurationRange).takeIf { feed.animatedDurationFilterActive },
    "Full color".takeIf { serverFilters.fullColor },
    nhentaiLanguageLabel(serverFilters.language).takeIf { serverFilters.language != NhentaiLanguageFilter.ANY },
    "Hide liked".takeIf { fabRestoreState.hideLiked },
    "Hide saved".takeIf { fabRestoreState.hideSaved },
    "Hide watched".takeIf { fabRestoreState.hideWatched },
)

private fun activeSearchFilterCount(state: SearchUiState, feed: SearchFeed, fabRestoreState: FeedFabRestoreState): Int =
    activeSearchFilterCount(
        appliedQuery = state.query.applied,
        animatedOnly = feed.animatedFilterActive,
        animatedDurationActive = feed.animatedDurationFilterActive,
        hideLiked = fabRestoreState.hideLiked,
        hideSaved = fabRestoreState.hideSaved,
        hideWatched = fabRestoreState.hideWatched,
        fullColor = state.query.nhentaiFullColorFilter,
        language = state.query.nhentaiLanguageFilter,
    )

private suspend fun resetSearchScroll(
    visibleResults: List<Post>,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    if (visibleResults.isNotEmpty()) {
        runCatchingPreservingCancellation {
            gridState.scrollToItem(index = 0, scrollOffset = 0)
        }
    }
    onAction(SearchAction.ScrollChanged(0, 0))
}

@Composable
private fun SearchFavoriteTags(
    state: SearchUiState,
    favoriteTags: Map<SourceKey, List<String>>,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAction: (SearchAction) -> Unit,
    onRemoveFavoriteTag: (SourceKey, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceDisplayOrder = remember(state.query.modeOptions) {
        state.query.modeOptions.mapNotNull { mode -> (mode as? QueryMode.Source)?.source }
    }
    val sections = remember(state.query.draft.mode, favoriteTags, sourceDisplayOrder) {
        favoriteTagSections(
            mode = state.query.draft.mode,
            favoriteTags = favoriteTags,
            sourceDisplayOrder = sourceDisplayOrder,
        )
    }
    val emptyMessage = remember(state.query.draft.mode) {
        when (val mode = state.query.draft.mode) {
            is QueryMode.Source -> "No favorite tags saved for ${mode.source.displayName()} yet."
            QueryMode.Unified -> "No favorite tags saved yet."
        }
    }
    FavoriteTagSheet(
        sections = sections,
        tagVideoCountProvider = tagVideoCountProvider,
        fetchTagVideoCounts = fetchTagVideoCounts,
        emptyMessage = emptyMessage,
        onAddTag = { tag -> onAction(SearchAction.AddIncludeTerm(SearchTerm(tag))) },
        onRemoveTag = onRemoveFavoriteTag,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SearchFilterSheet(
    state: SearchUiState,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    onAction: (SearchAction) -> Unit,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mode = state.query.draft.mode
    val animatedOnly = fabRestoreState.searchAnimationFilter(mode).animatedOnly
    FilterSheet(
        query = state.query.draft,
        onAction = onAction,
        animatedOnly = animatedOnly,
        onAnimatedOnlyChange = { enabled ->
            onFabRestoreStateChange(fabRestoreState.withSearchAnimationFilter(mode) { it.copy(animatedOnly = enabled) })
        },
        animatedDurationRange = feed.animatedDurationRange,
        onAnimatedDurationRangeChange = { range ->
            onFabRestoreStateChange(
                fabRestoreState.withSearchAnimationFilter(mode) {
                    it.copy(durationMinBucket = range.normalizedMinBucket, durationMaxBucket = range.normalizedMaxBucket)
                },
            )
        },
        showAnimatedOnlyFilter = !feed.isNhentaiSourceMode,
        hideLiked = fabRestoreState.hideLiked,
        onHideLikedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideLiked = enabled)) },
        hideSaved = fabRestoreState.hideSaved,
        onHideSavedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideSaved = enabled)) },
        hideWatched = fabRestoreState.hideWatched,
        onHideWatchedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideWatched = enabled)) },
        nhentaiFullColorFilter = state.query.nhentaiFullColorFilter,
        nhentaiLanguageFilter = state.query.nhentaiLanguageFilter,
        onReset = onReset,
        onDismiss = onDismiss,
    )
}

@Composable
private fun FilterSheet(
    query: Query,
    onAction: (SearchAction) -> Unit,
    animatedOnly: Boolean,
    onAnimatedOnlyChange: (Boolean) -> Unit,
    animatedDurationRange: AnimatedDurationRange,
    onAnimatedDurationRangeChange: (AnimatedDurationRange) -> Unit,
    showAnimatedOnlyFilter: Boolean,
    hideLiked: Boolean,
    onHideLikedChange: (Boolean) -> Unit,
    hideSaved: Boolean,
    onHideSavedChange: (Boolean) -> Unit,
    hideWatched: Boolean,
    onHideWatchedChange: (Boolean) -> Unit,
    nhentaiFullColorFilter: Boolean,
    nhentaiLanguageFilter: NhentaiLanguageFilter,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset) {
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), query.sort == mode) { onAction(SearchAction.SelectSort(mode)) }
            }
        }
        FilterSection("Show") {
            if (showAnimatedOnlyFilter) {
                FilterChoiceChip("Animated only", animatedOnly) { onAnimatedOnlyChange(!animatedOnly) }
            } else {
                FilterChoiceChip("Full color", nhentaiFullColorFilter) {
                    onAction(SearchAction.SetNhentaiFullColor(!nhentaiFullColorFilter))
                }
            }
            FilterChoiceChip("Hide liked", hideLiked) { onHideLikedChange(!hideLiked) }
            FilterChoiceChip("Hide saved", hideSaved) { onHideSavedChange(!hideSaved) }
            FilterChoiceChip("Hide watched", hideWatched) { onHideWatchedChange(!hideWatched) }
        }
        if (showAnimatedOnlyFilter) {
            AnimatedDurationRangeControl(range = animatedDurationRange, onRangeChange = onAnimatedDurationRangeChange)
        } else {
            FilterSection("Language") {
                NhentaiLanguageFilter.entries.forEach { filter ->
                    FilterChoiceChip(nhentaiLanguageLabel(filter), nhentaiLanguageFilter == filter) {
                        onAction(SearchAction.SetNhentaiLanguage(filter))
                    }
                }
            }
        }
        DateRangeFilterSection(
            range = query.dateRange,
            onPreset = { preset -> onAction(SearchAction.SetDateRangePreset(preset)) },
            onCustom = { range -> onAction(SearchAction.SetDateRange(range)) },
        )
    }
}

private fun nhentaiLanguageLabel(filter: NhentaiLanguageFilter): String = when (filter) {
    NhentaiLanguageFilter.ANY -> "Any"
    NhentaiLanguageFilter.ENGLISH -> "English"
    NhentaiLanguageFilter.CHINESE -> "Chinese"
    NhentaiLanguageFilter.JAPANESE -> "Japanese"
}

/** The filters that change what a source returns, so changing them needs a new search. */
private data class SearchServerFilters(
    val sort: SortMode,
    val dateRange: DateRange?,
    val fullColor: Boolean,
    val language: NhentaiLanguageFilter,
)

private fun SearchQueryUiState.serverFilters() =
    SearchServerFilters(draft.sort, draft.dateRange, nhentaiFullColorFilter, nhentaiLanguageFilter)

private val DEFAULT_SERVER_FILTERS = SearchServerFilters(SortMode.NEWEST, null, false, NhentaiLanguageFilter.ANY)

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}
