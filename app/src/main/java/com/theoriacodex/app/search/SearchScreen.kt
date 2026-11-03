package com.theoriacodex.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.R
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
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
    var searchBarDismissedByScroll by remember { mutableStateOf(false) }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFavoriteTagSheet by remember { mutableStateOf(false) }
    val actionPost = remember { SearchActionPostState() }
    val inputFocus = rememberSearchInputFocus()
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    ScrollToTopOnTabReselect(TopLevelDestination.Search) { gridState.animateScrollToItem(0) }
    val feed = rememberSearchFeed(state, fabRestoreState, likedPostIds, pendingLikePostIds, savedPostIds, watchedPostIds, durationStates, resolveUnknownAnimatedDurations)
    val serverFilters = SearchServerFilters(
        state.query.draft.sort, state.query.draft.dateRange,
        state.query.nhentaiFullColorFilter, state.query.nhentaiLanguageFilter,
    )
    val filterScroll = rememberFeedScrollSignal()
    var filterSheetBaseline by remember { mutableStateOf(serverFilters) }
    LaunchedEffect(feed.animatedDurationFilterActive) { onDurationFilterChanged(feed.animatedDurationFilterActive) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val pageDemand = rememberSearchScrollAndPaging(state, feed, gridState, resolveUnknownAnimatedDurations, onAction)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    searchBarDismissedByScroll = true
                    inputFocus.clear()
                }
            }
    }
    val currentServerFilters by androidx.compose.runtime.rememberUpdatedState(serverFilters)
    val applyDraft = {
        inputFocus.clear(force = true)
        onAction(SearchAction.ApplyDraft)
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }
    val closeComposer = {
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }
    val filterActions = SearchFilterActions(state, fabRestoreState, { currentServerFilters }, onAction, onFabRestoreStateChange, applyDraft)
    val sourcePicker: @Composable () -> Unit = {
        SearchSourcePicker(
            draftSourceScope = state.query.draftSourceScope,
            options = state.query.modeOptions,
            unifiedSourceCount = state.query.unifiedSources.size,
            unifiedSources = state.query.unifiedSources,
            onOpen = { inputFocus.clear() },
            onModeSelected = { mode ->
                searchBarDismissedByScroll = false
                onAction(SearchAction.SelectMode(mode))
            },
            onTemporarySourceToggled = { source ->
                searchBarDismissedByScroll = false
                onAction(SearchAction.ToggleTemporarySource(source))
            },
        )
    }
    val openPostActions: (Post) -> Unit = { post ->
        inputFocus.clear()
        actionPost.open(post, state.content.results, scope, resolvePostById)
    }
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                labels = searchFilterLabels(serverFilters, feed, fabRestoreState),
                contentDescription = "Filter and sort",
                onClick = {
                    inputFocus.clear()
                    filterSheetBaseline = serverFilters
                    showFilterSheet = true
                },
                onClear = { filterActions.clearFilters(serverFilters) },
                scrollSignal = filterScroll,
            )
        }
    ) { padding ->
        CollapsingSearchChrome(
            expanded = !searchBarDismissedByScroll,
            legacy = oldSearchBar,
            onExpand = { composerOpen = true },
            sourcePicker = sourcePicker,
            horizontalPadding = 16.dp,
            modifier = Modifier.then(inputFocus.rootModifier).fillMaxSize().padding(padding)
                .padding(top = if (oldSearchBar) 6.dp else 0.dp, bottom = 8.dp),
            inputField = {
                SearchSummaryBar(
                    summary = collapsedSearchContextSummary(state.query.draft, activeSearchFilterCount(state, feed, fabRestoreState)),
                    validationMessage = state.query.validationMessage,
                    showSearchAction = state.hasPendingChanges && !composerOpen,
                    onOpen = { composerOpen = true },
                    onSearch = { applyDraft() },
                    trailing = { if (oldSearchBar) sourcePicker() },
                )
            },
        ) {
            val cards = rememberFeedCardEnvironment(
                pixivUgoiraClient, likedPostIds, feed.durationMetadata.knownDurationMsByPostId, durationStateForPost,
                onToggleLike, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown,
            )
            SearchResultsBody(state, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction, openPostActions)
        }
    }

    actionPost.post?.let { post ->
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
                )
            },
        )
    }

    if (composerOpen) {
        SearchComposer(
            state = state,
            onAction = onAction,
            sourcePicker = sourcePicker,
            onApply = applyDraft,
            onReset = {
                onAction(SearchAction.ClearDraft)
                onAction(SearchAction.ClearAutocomplete)
                showFilterSheet = false
                scope.launch { resetSearchScroll(feed.visibleResults, gridState, onAction) }
            },
            onUndo = {
                onAction(SearchAction.UndoSearch)
                onAction(SearchAction.ClearAutocomplete)
                showFilterSheet = false
            },
            onOpenFavorites = { showFavoriteTagSheet = true },
            onOpenSourceAccounts = onOpenSourceAccounts,
            onDigitsCommitted = { scope.launch { resetSearchScroll(feed.visibleResults, gridState, onAction) } },
            onDismiss = closeComposer,
            savedSearches = savedSearches,
            onOpenSavedSearch = { entry ->
                closeComposer()
                onOpenSavedSearch(entry)
            },
        )
    }

    if (showFavoriteTagSheet) {
        SearchFavoriteTags(state, favoriteTags, tagVideoCountProvider, fetchTagVideoCounts, onAction, onRemoveFavoriteTag,
            onDismiss = { showFavoriteTagSheet = false })
    }

    if (showFilterSheet) {
        SearchFilterSheet(state, feed, fabRestoreState, onAction, onFabRestoreStateChange,
            onReset = filterActions::resetFilterDraft,
            // Results change once, when the filters are settled, however many were adjusted.
            onDismiss = {
                showFilterSheet = false
                if (serverFilters != filterSheetBaseline) applyDraft()
            })
    }
}

/** What the results grid shows after visibility filters, known durations, and related posts apply. */
private class SearchFeed(
    val animatedFilterActive: Boolean,
    val animatedDurationRange: AnimatedDurationRange,
    val animatedDurationFilterActive: Boolean,
    val visibilityFilters: SearchVisibilityFilters,
    val isNhentaiSourceMode: Boolean,
    val displayResults: List<Post>,
    val durationMetadata: DurationFilterMetadata,
    val visibleResults: List<Post>,
    val related: RelatedFeedPresentation,
)

@Composable
private fun rememberSearchFeed(
    state: SearchUiState,
    fabRestoreState: FeedFabRestoreState,
    likedPostIds: Set<PostId>,
    pendingLikePostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId>,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
): SearchFeed {
    val animationRestoreState = fabRestoreState.searchAnimationFilter(state.query.draft.mode)
    val hideLiked = fabRestoreState.hideLiked
    val hideSaved = fabRestoreState.hideSaved
    val hideWatched = fabRestoreState.hideWatched
    val durationMinBucket = animationRestoreState.durationMinBucket
    val durationMaxBucket = animationRestoreState.durationMaxBucket
    val isNhentaiSourceMode = state.query.draft.mode == QueryMode.Source(SourceKey.NHENTAI)
    val animatedFilterActive = animationRestoreState.animatedOnly && !isNhentaiSourceMode
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket)
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange && !isNhentaiSourceMode
    val visibilityFilters = remember(
        animatedFilterActive, hideLiked, hideSaved, hideWatched, animatedDurationRange, state.query.applied.dateRange,
    ) {
        SearchVisibilityFilters(
            dateRange = state.query.applied.dateRange,
            animatedOnly = animatedFilterActive,
            hideLiked = hideLiked,
            hideSaved = hideSaved,
            hideWatched = hideWatched,
            animatedDurationRange = animatedDurationRange,
        )
    }
    val unknownAnimatedDurationPolicy = remember(resolveUnknownAnimatedDurations) {
        if (resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
    }
    val displayResults = remember(state.content.results, state.content.displayVersion, state.query.appliedQueryHash) {
        state.content.results
    }
    val durationMetadata = rememberDurationFilterMetadata(displayResults, durationStates, animatedDurationFilterActive)
    val acquiredDurations = durationMetadata.knownDurationMsByPostId
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(state.relatedPosts, pendingLikePostIds) {
        state.relatedPosts.retainedLikedPostIds + pendingLikePostIds
    })
    val visibleResults = remember(
        displayResults, visibilityFilters, likedPostIds, retainedLikedPostIds, savedPostIds, watchedPostIds,
        unknownAnimatedDurationPolicy, acquiredDurations,
    ) {
        filterSearchResults(
            results = displayResults,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            retainedLikedPostIds = retainedLikedPostIds,
            savedPostIds = savedPostIds,
            watchedPostIds = watchedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
            knownDurationMsByPostId = acquiredDurations,
        )
    }
    val related = rememberRelatedFeedPresentation(
        pendingLikePostIds = pendingLikePostIds,
        canonicalPosts = displayResults, visibleCanonicalPosts = visibleResults,
        relatedState = state.relatedPosts, visibilityFilters = visibilityFilters,
        likedPostIds = likedPostIds, savedPostIds = savedPostIds, watchedPostIds = watchedPostIds,
        unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy, durationStates = durationStates,
        durationFilterActive = animatedDurationFilterActive,
    )
    return remember(
        animatedFilterActive, animatedDurationRange, animatedDurationFilterActive, visibilityFilters,
        isNhentaiSourceMode, displayResults, durationMetadata, visibleResults, related,
    ) {
        SearchFeed(
            animatedFilterActive, animatedDurationRange, animatedDurationFilterActive, visibilityFilters,
            isNhentaiSourceMode, displayResults, durationMetadata, visibleResults, related,
        )
    }
}

/** Restores and persists the grid position, then returns the demand that loads further pages. */
@Composable
private fun rememberSearchScrollAndPaging(
    state: SearchUiState,
    feed: SearchFeed,
    gridState: LazyStaggeredGridState,
    resolveUnknownAnimatedDurations: Boolean,
    onAction: (SearchAction) -> Unit,
): FeedPageDemandControl {
    val queryHash = state.query.appliedQueryHash
    val projection = feed.related.projection
    val scrollRestoration = (state.restoration as? SearchRestorationUiState.Restored)
        ?.takeIf { restored -> restored.scrollState != null }
    SearchScrollRestorationEffect(
        state, projection, gridState, onAction,
        visibilityReady = !feed.animatedDurationFilterActive || feed.durationMetadata.isVisibilityResolved(
            feed.displayResults.getOrNull(scrollRestoration?.scrollState?.firstVisibleItemIndex ?: -1),
            resolveInBackground = resolveUnknownAnimatedDurations,
        ),
    )
    SearchScrollPersistenceEffect(queryHash, state.restoration, projection, gridState, onAction)
    return rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = queryHash,
            completedGeneration = state.execution.lastCompletedRequestId ?: 0L,
            canonicalCount = feed.displayResults.size,
            visibleCount = feed.visibleResults.size,
            canLoadMore = state.content.canLoadMore,
            ready = state.content.hasExecutedSearch,
            refreshing = state.loading,
            paging = state.loadingMore,
            failed = state.content.error != null,
            resolvingDurations = feed.durationMetadata.readiness.isResolving,
            requiredCanonicalIndex = scrollRestoration?.scrollState?.firstVisibleItemIndex,
        ),
        filters = feed.visibilityFilters,
        gridState = gridState,
        presentedItemCount = projection.entries.size,
        canonicalIndexForVisibleItems = projection::greatestVisibleCanonicalIndex,
        onLoadNextPage = { onAction(SearchAction.LoadNextPage) },
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
    private val currentServerFilters: () -> SearchServerFilters,
    private val onAction: (SearchAction) -> Unit,
    private val onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    private val applyDraft: () -> Unit,
) {
    private fun applyServerFilters(filters: SearchServerFilters) {
        onAction(SearchAction.SelectSort(filters.sort))
        onAction(SearchAction.SetDateRange(filters.dateRange))
        // These two rewrite the draft's nhentai terms, so only a real change may dispatch them.
        if (filters.fullColor != currentServerFilters().fullColor) onAction(SearchAction.SetNhentaiFullColor(filters.fullColor))
        if (filters.language != currentServerFilters().language) onAction(SearchAction.SetNhentaiLanguage(filters.language))
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
private fun ColumnScope.SearchResultsBody(
    state: SearchUiState,
    feed: SearchFeed,
    cards: com.theoriacodex.app.ui.components.FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    val clearFocusInteraction = remember { MutableInteractionSource() }
    val tapToClearFocus = Modifier
        .weight(1f)
        .clickable(interactionSource = clearFocusInteraction, indication = null) { inputFocus.clear() }
    val sourceAuthErrorMessage = remember(state.content.statuses) { buildSourceAuthErrorMessage(state.content.statuses) }
    val retrySearch = { inputFocus.clear(); onAction(SearchAction.Retry) }
    state.pageFailure?.let { SearchFailureSurface(it, retrySearch) }
    // Results show the pull indicator instead; this keeps refreshes of an emptied feed visible.
    if (state.retainsResultsWhileRefreshing && feed.related.projection.entries.isEmpty()) SearchRefreshProgress()
    when {
        state.loading && !state.retainsResultsWhileRefreshing -> FeedLoadingState(modifier = tapToClearFocus.fillMaxWidth())
        state.content.error != null && state.pageFailure == null -> {
            SearchFailureSurface(requireNotNull(state.content.error), retrySearch, tapToClearFocus)
        }
        feed.related.projection.entries.isEmpty() -> Box(modifier = tapToClearFocus) {
            SearchEmptyResults(state, feed, pageDemand, sourceAuthErrorMessage, retrySearch)
        }
        else -> Column(modifier = tapToClearFocus, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceAuthErrorMessage?.let { authMessage ->
                ErrorBlock(title = "Source account required", message = authMessage)
            }
            PullToRefreshBox(
                isRefreshing = state.retainsResultsWhileRefreshing,
                onRefresh = retrySearch,
                modifier = Modifier.weight(1f),
            ) {
                SearchResultsGrid(state, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction, openPostActions)
            }
        }
    }
}

@Composable
private fun SearchEmptyResults(
    state: SearchUiState,
    feed: SearchFeed,
    pageDemand: FeedPageDemandControl,
    sourceAuthErrorMessage: String?,
    onRetry: () -> Unit,
) {
    val sourceFailureMessage = remember(state.content.statuses) { buildSourceFailureMessage(state.content.statuses) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedContinueLoading(pageDemand)
        sourceAuthErrorMessage?.let { authMessage ->
            ErrorBlock(title = "Source account required", message = authMessage)
        }
        sourceFailureMessage?.let { failureMessage -> ErrorBlock(message = failureMessage, onRetry = onRetry) }
        if (feed.durationMetadata.readiness.isResolving) {
            EmptyBlock(hasPendingChanges = false, messageOverride = "Resolving durations…")
        } else if (!state.content.hasExecutedSearch && !feed.animatedFilterActive) {
            SearchStartSplash(modifier = Modifier.fillMaxWidth())
        } else {
            EmptyBlock(
                hasPendingChanges = state.hasPendingChanges,
                messageOverride = buildEmptySearchMessage(
                    sourceResults = state.content.results,
                    visibilityFilters = feed.visibilityFilters,
                    loadingMore = state.loadingMore,
                    canLoadMore = state.content.canLoadMore,
                ),
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    state: SearchUiState,
    feed: SearchFeed,
    cards: com.theoriacodex.app.ui.components.FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    TwoColumnProjectedPostStaggeredGrid(
        projection = feed.related.projection,
        state = gridState,
        modifier = Modifier.fillMaxSize().testTag("Search results"),
        showPagingTile = state.loadingMore,
        footerContent = if (pageDemand.showContinue) {
            { FeedContinueLoading(pageDemand) }
        } else {
            null
        },
        footerMessage = if (feed.durationMetadata.readiness.isResolving) "Resolving durations…" else null,
        postContent = { _, post ->
            FeedPostCard(
                post = post,
                environment = cards,
                showWatchHistory = true,
                showSourceBadge = state.query.appliedSourceScope !is SearchSourceScope.Single,
                resolvePostById = resolvePostById,
                recoverPostMedia = recoverPostMedia,
                onClick = {
                    inputFocus.clear()
                    onAction(SearchAction.OpenResult(
                        postId = post.id,
                        visibleResults = feed.visibleResults,
                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                        visibilityFilters = feed.visibilityFilters,
                    ))
                },
                onLongPress = { openPostActions(post) },
            )
        },
        shelfContent = {
            FeedRelatedPostsShelf(
                state = state.relatedPosts,
                presentation = feed.related,
                environment = cards,
                recoverPostMedia = recoverPostMedia,
                onOpenPost = { posts, index -> onAction(SearchAction.OpenRelatedResult(index, posts)) },
                onLongPress = openPostActions,
                onDismiss = { onAction(SearchAction.DismissRelatedPosts) },
                onRetry = { onAction(SearchAction.RetryRelatedPosts) },
                onPreviousPage = { onAction(SearchAction.ShowPreviousRelatedPosts) },
                onNextPage = { onAction(SearchAction.ShowNextRelatedPosts) },
            )
        },
    )
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
private fun SearchStartSplash(
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.theoria_splash_mark),
                contentDescription = "Theoria splash",
                modifier = Modifier.size(180.dp),
            )
            Text(
                text = "Add tags and press Apply to start searching.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
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

private val DEFAULT_SERVER_FILTERS = SearchServerFilters(SortMode.NEWEST, null, false, NhentaiLanguageFilter.ANY)

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}
