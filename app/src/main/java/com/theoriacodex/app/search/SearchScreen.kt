package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SavedSearchEntry
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import com.theoriacodex.domain.model.DateRange
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.theoriacodex.app.viewer.ViewerReturnCorners
import com.theoriacodex.app.viewer.viewerReturnCorners
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.LocalViewerReturnGrid
import com.theoriacodex.app.viewer.LocalViewerReturnShelf
import androidx.compose.ui.layout.LayoutCoordinates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.SuccessResult
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.media.animatedDurationLabel
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.MediaDeliveryActivation
import com.theoriacodex.app.media.mergeResolvedPostForPresentation
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.R
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.isRule34Family
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.app.viewer.PixivUgoiraPlayer
import com.theoriacodex.app.viewer.UgoiraSizeBucket
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale
import kotlinx.coroutines.delay
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
    val input = state.suggestions.input
    val animationRestoreState = fabRestoreState.searchAnimationFilter(state.query.draft.mode)
    val animatedOnly = animationRestoreState.animatedOnly
    val hideLiked = fabRestoreState.hideLiked
    val hideSaved = fabRestoreState.hideSaved
    val hideWatched = fabRestoreState.hideWatched
    val durationMinBucket = animationRestoreState.durationMinBucket
    val durationMaxBucket = animationRestoreState.durationMaxBucket
    var searchBarDismissedByScroll by remember { mutableStateOf(false) }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFavoriteTagSheet by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionPostResolving by remember { mutableStateOf(false) }
    val inputFocus = rememberSearchInputFocus()
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    ScrollToTopOnTabReselect(TopLevelDestination.Search) { gridState.animateScrollToItem(0) }
    val queryHash = state.query.appliedQueryHash
    val isNhentaiSourceMode = state.query.draft.mode == QueryMode.Source(SourceKey.NHENTAI)
    val animatedFilterActive = animatedOnly && !isNhentaiSourceMode
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(
            minBucket = durationMinBucket,
            maxBucket = durationMaxBucket,
        )
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange && !isNhentaiSourceMode
    val serverFilters = SearchServerFilters(
        state.query.draft.sort, state.query.draft.dateRange,
        state.query.nhentaiFullColorFilter, state.query.nhentaiLanguageFilter,
    )
    val filterLabels = listOfNotNull(
        sortModeLabel(serverFilters.sort).takeIf { serverFilters.sort != SortMode.NEWEST },
        serverFilters.dateRange?.let { dateRangeLabel(it, System.currentTimeMillis()) },
        "Animated".takeIf { animatedFilterActive },
        animatedDurationRangeLabel(animatedDurationRange).takeIf { animatedDurationFilterActive },
        "Full color".takeIf { serverFilters.fullColor },
        nhentaiLanguageLabel(serverFilters.language).takeIf { serverFilters.language != NhentaiLanguageFilter.ANY },
        "Hide liked".takeIf { hideLiked },
        "Hide saved".takeIf { hideSaved },
        "Hide watched".takeIf { hideWatched },
    )
    val filterScroll = rememberFeedScrollSignal()
    var filterSheetBaseline by remember { mutableStateOf(serverFilters) }
    val appliedFilterCount = activeSearchFilterCount(
        appliedQuery = state.query.applied,
        animatedOnly = animatedFilterActive,
        animatedDurationActive = animatedDurationFilterActive,
        hideLiked = hideLiked,
        hideSaved = hideSaved,
        hideWatched = hideWatched,
        fullColor = state.query.nhentaiFullColorFilter,
        language = state.query.nhentaiLanguageFilter,
    )
    val searchBarSummary = collapsedSearchContextSummary(
        query = state.query.draft,
        activeFilterCount = appliedFilterCount,
    )
    val visibilityFilters = remember(
        animatedFilterActive,
        hideLiked,
        hideSaved,
        hideWatched,
        animatedDurationRange,
        state.query.applied.dateRange,
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
    val displayResults = remember(state.content.results, state.content.displayVersion, queryHash) {
        state.content.results
    }
    val durationMetadata = rememberDurationFilterMetadata(displayResults, durationStates, animatedDurationFilterActive)
    val acquiredDurations = durationMetadata.knownDurationMsByPostId
    val durationReadiness = durationMetadata.readiness
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(state.relatedPosts, pendingLikePostIds) {
        state.relatedPosts.retainedLikedPostIds + pendingLikePostIds
    })
    val visibleResults = remember(
        displayResults,
        visibilityFilters,
        likedPostIds,
        retainedLikedPostIds,
        savedPostIds,
        watchedPostIds,
        unknownAnimatedDurationPolicy,
        acquiredDurations,
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
    val relatedPresentation = rememberRelatedFeedPresentation(
        pendingLikePostIds = pendingLikePostIds,
        canonicalPosts = displayResults, visibleCanonicalPosts = visibleResults,
        relatedState = state.relatedPosts, visibilityFilters = visibilityFilters,
        likedPostIds = likedPostIds, savedPostIds = savedPostIds, watchedPostIds = watchedPostIds,
        unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy, durationStates = durationStates,
        durationFilterActive = animatedDurationFilterActive,
    )
    val feedProjection = relatedPresentation.projection
    LaunchedEffect(animatedDurationFilterActive) {
        onDurationFilterChanged(animatedDurationFilterActive)
    }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    fun openPostActionSheet(post: Post) {
        inputFocus.clear()
        val displayPost = state.content.results.firstOrNull { candidate -> candidate.id == post.id } ?: post
        selectedActionPost = displayPost
        selectedActionPostResolving = false
        if (displayPost.hasActionableTags()) return

        selectedActionPostResolving = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation {
                resolvePostById(post.id)
            }.getOrNull()
            if (selectedActionPost?.id == post.id) {
                selectedActionPost = resolved ?: displayPost
                selectedActionPostResolving = false
            }
        }
    }
    val sourceDisplayOrder = remember(state.query.modeOptions) {
        state.query.modeOptions
            .mapNotNull { mode -> (mode as? QueryMode.Source)?.source }
    }
    val favoriteSections = remember(state.query.draft.mode, favoriteTags, sourceDisplayOrder) {
        favoriteTagSections(
            mode = state.query.draft.mode,
            favoriteTags = favoriteTags,
            sourceDisplayOrder = sourceDisplayOrder,
        )
    }
    val favoriteTagEmptyMessage = remember(state.query.draft.mode) {
        when (val mode = state.query.draft.mode) {
            is QueryMode.Source -> "No favorite tags saved for ${mode.source.displayName()} yet."
            QueryMode.Unified -> "No favorite tags saved yet."
        }
    }
    suspend fun resetScrollToTop() {
        if (visibleResults.isNotEmpty()) {
            runCatchingPreservingCancellation {
                gridState.scrollToItem(index = 0, scrollOffset = 0)
            }
        }
        onAction(SearchAction.ScrollChanged(0, 0))
    }

    val scrollRestoration = (state.restoration as? SearchRestorationUiState.Restored)
        ?.takeIf { restored -> restored.scrollState != null }
    SearchScrollRestorationEffect(
        state, feedProjection, gridState, onAction,
        visibilityReady = !animatedDurationFilterActive || durationMetadata.isVisibilityResolved(
            displayResults.getOrNull(scrollRestoration?.scrollState?.firstVisibleItemIndex ?: -1),
            resolveInBackground = resolveUnknownAnimatedDurations,
        ),
    )
    SearchScrollPersistenceEffect(
        queryHash, state.restoration, feedProjection, gridState, onAction,
    )

    val pageDemand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = queryHash,
            completedGeneration = state.execution.lastCompletedRequestId ?: 0L,
            canonicalCount = displayResults.size,
            visibleCount = visibleResults.size,
            canLoadMore = state.content.canLoadMore,
            ready = state.content.hasExecutedSearch,
            refreshing = state.loading,
            paging = state.loadingMore,
            failed = state.content.error != null,
            resolvingDurations = durationReadiness.isResolving,
            requiredCanonicalIndex = scrollRestoration?.scrollState?.firstVisibleItemIndex,
        ),
        filters = visibilityFilters,
        gridState = gridState,
        presentedItemCount = feedProjection.entries.size,
        canonicalIndexForVisibleItems = feedProjection::greatestVisibleCanonicalIndex,
        onLoadNextPage = { onAction(SearchAction.LoadNextPage) },
    )

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    searchBarDismissedByScroll = true
                    inputFocus.clear()
                }
            }
    }

    val sourceAuthErrorMessage = remember(state.content.statuses) {
        buildSourceAuthErrorMessage(state.content.statuses)
    }
    val sourceFailureMessage = remember(state.content.statuses) {
        buildSourceFailureMessage(state.content.statuses)
    }
    val clearFocusInteraction = remember { MutableInteractionSource() }

    val currentServerFilters by androidx.compose.runtime.rememberUpdatedState(serverFilters)

    fun applyServerFilters(filters: SearchServerFilters) {
        onAction(SearchAction.SelectSort(filters.sort))
        onAction(SearchAction.SetDateRange(filters.dateRange))
        // These two rewrite the draft's nhentai terms, so only a real change may dispatch them.
        if (filters.fullColor != currentServerFilters.fullColor) onAction(SearchAction.SetNhentaiFullColor(filters.fullColor))
        if (filters.language != currentServerFilters.language) onAction(SearchAction.SetNhentaiLanguage(filters.language))
    }

    fun closeComposer() {
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }

    fun applyDraft() {
        inputFocus.clear(force = true)
        onAction(SearchAction.ApplyDraft)
        onAction(SearchAction.ClearAutocomplete)
        composerOpen = false
        searchBarDismissedByScroll = false
    }

    fun resetFilterDraft() {
        applyServerFilters(SearchServerFilters(SortMode.NEWEST, null, false, NhentaiLanguageFilter.ANY))
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

    // Results change once, when the filters are settled, however many were adjusted.
    fun closeFilterSheet() {
        showFilterSheet = false
        if (serverFilters != filterSheetBaseline) applyDraft()
    }

    fun clearFilters(): () -> Unit {
        val previousFab = fabRestoreState
        val previousServerFilters = serverFilters
        val serverFiltersActive = serverFilters != SearchServerFilters(SortMode.NEWEST, null, false, NhentaiLanguageFilter.ANY)
        resetFilterDraft()
        if (serverFiltersActive) applyDraft()
        return {
            onFabRestoreStateChange(previousFab)
            applyServerFilters(previousServerFilters)
            if (serverFiltersActive) applyDraft()
        }
    }

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

    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                labels = filterLabels,
                contentDescription = "Filter and sort",
                onClick = {
                    inputFocus.clear()
                    filterSheetBaseline = serverFilters
                    showFilterSheet = true
                },
                onClear = ::clearFilters,
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
                    summary = searchBarSummary,
                    validationMessage = state.query.validationMessage,
                    showSearchAction = state.hasPendingChanges && !composerOpen,
                    onOpen = { composerOpen = true },
                    onSearch = { applyDraft() },
                    trailing = { if (oldSearchBar) sourcePicker() },
                )
            },
        ) {
            val retrySearch = { inputFocus.clear(); onAction(SearchAction.Retry) }
            state.pageFailure?.let { SearchFailureSurface(it, retrySearch) }
            // Results show the pull indicator instead; this keeps refreshes of an emptied feed visible.
            if (state.retainsResultsWhileRefreshing && feedProjection.entries.isEmpty()) SearchRefreshProgress()
            when {
                state.loading && !state.retainsResultsWhileRefreshing -> {
                    FeedLoadingState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { inputFocus.clear() },
                    )
                }
                state.content.error != null && state.pageFailure == null -> {
                    SearchFailureSurface(requireNotNull(state.content.error), retrySearch,
                        Modifier.weight(1f).clickable(interactionSource = clearFocusInteraction, indication = null) {
                            inputFocus.clear()
                        })
                }
                feedProjection.entries.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { inputFocus.clear() }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeedContinueLoading(pageDemand)
                            sourceAuthErrorMessage?.let { authMessage ->
                                ErrorBlock(
                                    title = "Source account required",
                                    message = authMessage,
                                )
                            }
                            sourceFailureMessage?.let { failureMessage ->
                                ErrorBlock(
                                    message = failureMessage,
                                    onRetry = {
                                        inputFocus.clear()
                                        onAction(SearchAction.Retry)
                                    },
                                )
                            }
                            if (durationReadiness.isResolving) {
                                EmptyBlock(
                                    hasPendingChanges = false,
                                    messageOverride = "Resolving durations…",
                                )
                            } else if (!state.content.hasExecutedSearch && !animatedFilterActive) {
                                SearchStartSplash(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                EmptyBlock(
                                    hasPendingChanges = state.hasPendingChanges,
                                    messageOverride = buildEmptySearchMessage(
                                        sourceResults = state.content.results,
                                        visibilityFilters = visibilityFilters,
                                        loadingMore = state.loadingMore,
                                        canLoadMore = state.content.canLoadMore,
                                    ),
                                )
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { inputFocus.clear() },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sourceAuthErrorMessage?.let { authMessage ->
                            ErrorBlock(
                                title = "Source account required",
                                message = authMessage,
                            )
                        }
                        PullToRefreshBox(
                            isRefreshing = state.retainsResultsWhileRefreshing,
                            onRefresh = { inputFocus.clear(); onAction(SearchAction.Retry) },
                            modifier = Modifier.weight(1f),
                        ) {
                        TwoColumnProjectedPostStaggeredGrid(
                            projection = feedProjection,
                            state = gridState,
                            modifier = Modifier.fillMaxSize().testTag("Search results"),
                            showPagingTile = state.loadingMore,
                            footerContent = if (pageDemand.showContinue) {
                                { FeedContinueLoading(pageDemand) }
                            } else {
                                null
                            },
                            footerMessage = if (durationReadiness.isResolving) {
                                "Resolving durations…"
                            } else {
                                null
                            },
                            postContent = { _, post ->
                                val observedDurationMs = observedMediaDurationMs(post, durationStateForPost)
                                SearchResultCard(
                                    post = post,
                                    showWatchHistory = true,
                                    pixivUgoiraClient = pixivUgoiraClient,
                                    acquiredDurationMs = observedDurationMs ?: acquiredDurations[post.id],
                                    showSourceBadge = state.query.appliedSourceScope !is SearchSourceScope.Single,
                                    liked = post.id in likedPostIds,
                                    onToggleLike = onToggleLike?.let { toggle ->
                                        { toggle(post) }
                                    },
                                    resolvePostById = resolvePostById,
                                    recoverPostMedia = { failedPost, failedMedia ->
                                        recoverPostMedia(failedPost, failedMedia)
                                    },
                                    onClick = {
                                        inputFocus.clear()
                                        onAction(SearchAction.OpenResult(
                                            postId = post.id,
                                            visibleResults = visibleResults,
                                            scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                            visibilityFilters = visibilityFilters,
                                        ))
                                    },
                                    onLongPress = { openPostActionSheet(post) },
                                    onViewportChanged = { visible ->
                                        onDurationPostVisibilityChanged(post, visible)
                                    },
                                    onAuthoritativeDurationKnown = { durationMs ->
                                        onAuthoritativeDurationKnown(post, durationMs)
                                    },
                                )
                            },
                            shelfContent = {
                                SearchRelatedPostsShelf(
                                    state = state.relatedPosts,
                                    presentation = relatedPresentation,
                                    likedPostIds = likedPostIds,
                                    pixivUgoiraClient = pixivUgoiraClient,
                                    durationStateForPost = durationStateForPost,
                                    recoverPostMedia = recoverPostMedia,
                                    onToggleLike = onToggleLike,
                                    onAction = onAction,
                                    onLongPress = ::openPostActionSheet,
                                    onViewportChanged = onDurationPostVisibilityChanged,
                                    onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                                )
                            },
                        )
                        }
                    }
                }
            }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = {
                selectedActionPost = null
                selectedActionPostResolving = false
            },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            saveToCodexQuery = state.query.applied,
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                PostTagActionSection(
                    draftQuery = state.query.draft,
                    loading = selectedActionPostResolving && !post.hasActionableTags(),
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
            onApply = ::applyDraft,
            onReset = {
                onAction(SearchAction.ClearDraft)
                onAction(SearchAction.ClearAutocomplete)
                showFilterSheet = false
                scope.launch { resetScrollToTop() }
            },
            onUndo = {
                onAction(SearchAction.UndoSearch)
                onAction(SearchAction.ClearAutocomplete)
                showFilterSheet = false
            },
            onOpenFavorites = { showFavoriteTagSheet = true },
            onOpenSourceAccounts = onOpenSourceAccounts,
            onDigitsCommitted = { scope.launch { resetScrollToTop() } },
            onDismiss = ::closeComposer,
            savedSearches = savedSearches,
            onOpenSavedSearch = { entry ->
                closeComposer()
                onOpenSavedSearch(entry)
            },
        )
    }

    if (showFavoriteTagSheet) {
        FavoriteTagSheet(
            sections = favoriteSections,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            emptyMessage = favoriteTagEmptyMessage,
            onAddTag = { tag ->
                onAction(SearchAction.AddIncludeTerm(SearchTerm(tag)))
            },
            onRemoveTag = { source, tag ->
                onRemoveFavoriteTag(source, tag)
            },
            onDismiss = { showFavoriteTagSheet = false },
        )
    }

    if (showFilterSheet) {
        FilterSheet(
            query = state.query.draft,
            onAction = onAction,
            animatedOnly = animatedOnly,
            onAnimatedOnlyChange = { enabled ->
                onFabRestoreStateChange(
                    fabRestoreState.withSearchAnimationFilter(state.query.draft.mode) { restoreState ->
                        restoreState.copy(animatedOnly = enabled)
                    },
                )
            },
            animatedDurationRange = animatedDurationRange,
            onAnimatedDurationRangeChange = { range ->
                onFabRestoreStateChange(
                    fabRestoreState.withSearchAnimationFilter(state.query.draft.mode) { restoreState ->
                        restoreState.copy(
                            durationMinBucket = range.normalizedMinBucket,
                            durationMaxBucket = range.normalizedMaxBucket,
                        )
                    },
                )
            },
            showAnimatedOnlyFilter = !isNhentaiSourceMode,
            hideLiked = hideLiked,
            onHideLikedChange = { enabled ->
                onFabRestoreStateChange(fabRestoreState.copy(hideLiked = enabled))
            },
            hideSaved = hideSaved,
            onHideSavedChange = { enabled ->
                onFabRestoreStateChange(fabRestoreState.copy(hideSaved = enabled))
            },
            hideWatched = hideWatched,
            onHideWatchedChange = { enabled ->
                onFabRestoreStateChange(fabRestoreState.copy(hideWatched = enabled))
            },
            nhentaiFullColorFilter = state.query.nhentaiFullColorFilter,
            nhentaiLanguageFilter = state.query.nhentaiLanguageFilter,
            onReset = ::resetFilterDraft,
            onDismiss = ::closeFilterSheet,
        )
    }
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
@OptIn(ExperimentalFoundationApi::class)
fun SearchResultCard(
    post: Post,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurationMs: Long? = null,
    showSourceBadge: Boolean = false,
    metadataLabel: String? = null,
    viewedMediaNumber: Int? = null,
    showWatchHistory: Boolean = false,
    mediaAspectRatio: Float? = null,
    showTitle: Boolean = true,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    refreshOnPreviewError: Boolean = false,
    playbackDiagnosticsEnabled: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onViewportChanged: (Boolean) -> Unit = {},
    onAuthoritativeDurationKnown: (Long) -> Unit = {},
) {
    val viewerReturn = LocalViewerReturnTransition.current
    val viewerReturnGrid = LocalViewerReturnGrid.current
    val viewerReturnShelf = LocalViewerReturnShelf.current
    var viewerMediaCoordinates by remember(post.id) { mutableStateOf<LayoutCoordinates?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isInViewport by remember(post.id) { mutableStateOf(false) }
    var isLifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val playbackActive = shouldFeedMediaPlay(
        isInViewport = isInViewport,
        isLifecycleStarted = isLifecycleStarted,
    )
    var resolvedPostOverride by remember(post.id) { mutableStateOf<Post?>(null) }
    var resolutionAttempted by remember(post.id) { mutableStateOf(false) }
    val mediaRecoveryAttemptedUrls = remember(post.id) { mutableSetOf<String>() }
    val watchPresentation = LocalPostWatchPresentation.current.value
    val lastPosition = watchPresentation.positions[post.id]
    val lastMediaNumber = lastPosition?.mediaNumber
    val watchedEntry = watchPresentation.watched[post.id].takeIf { showWatchHistory }
    val cardViewedMediaNumber = viewedMediaNumber ?: watchedEntry?.maxViewedMediaNumber
    // A bare "2h ago" over a result reads as its upload date, so watch history gets its own mark.
    val watchedDescription = watchedEntry?.takeIf { metadataLabel == null }?.let {
        val watchedAt = relativeTimeLabel(System.currentTimeMillis(), lastPosition?.lastViewedAt ?: it.viewedAtEpochMs)
        "Watched ${watchedAt.lowercase(Locale.US)}"
    }
    val effectivePost = resolvedPostOverride?.let { resolved ->
        mergeResolvedPostForPresentation(original = post, resolved = resolved)
    } ?: post

    val watchedPost = watchPresentation.watched[post.id]?.post
    val previewPost = remember(effectivePost, watchedPost, lastMediaNumber) {
        val previewSource = if (lastMediaNumber != null && lastMediaNumber > postMediaItems(effectivePost).size && watchedPost != null) {
            mergeResolvedPostForPresentation(original = effectivePost, resolved = watchedPost)
        } else {
            effectivePost
        }
        postForCardPreview(previewSource, lastMediaNumber)
    }

    DisposableEffect(post.id) {
        onDispose {
            if (isInViewport) onViewportChanged(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isLifecycleStarted = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestResolvedCardPreview(force: Boolean = false) {
        if (resolutionAttempted) return
        val resolver = resolvePostById ?: return
        if (
            !force &&
            post.id.source != SourceKey.RULE34VIDEO &&
            post.id.source != SourceKey.RULE34GEN
        ) return
        resolutionAttempted = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation {
                resolver(post.id)
            }.getOrNull() ?: return@launch
            resolvedPostOverride = resolved
        }
    }

    fun canRecoverMedia(error: Throwable, location: String?): Boolean =
        recoverPostMedia != null && isHttpNotFound(error) && mediaRecoveryAttemptedUrls.add(location.orEmpty())

    /** Returns whether the provider supplied replacement media for this card. */
    suspend fun recoverCardMedia(failedRef: ImageRef): Boolean {
        val recovered = runCatchingPreservingCancellation {
            recoverPostMedia?.invoke(effectivePost, failedRef)
        }.getOrNull()
        if (recovered == null || recovered == effectivePost) return false
        resolvedPostOverride = recovered
        return true
    }

    var mediaCorners by remember { mutableStateOf(ViewerReturnCorners()) }
    val cardShape = CardDefaults.elevatedShape
    val layoutDirection = LocalLayoutDirection.current
    val cardDensity = LocalDensity.current
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(searchCardTestTag(post.id))
            .combinedClickable(
                onClick = {
                    viewerReturn?.openedFrom(
                        viewerReturnGrid,
                        viewerMediaCoordinates?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false),
                        shelfId = viewerReturnShelf,
                        corners = mediaCorners,
                    )
                    onClick()
                },
                onLongClick = onLongPress,
            ),
    ) {
        val title = effectivePost.displayTitleOrNull()
        val durationLabel = animatedDurationLabel(effectivePost, acquiredDurationMs)
        val supportingContent = searchCardSupportingContent(
            title = title,
            metadataLabel = metadataLabel,
            showWatchedBadge = watchedDescription != null,
            showSourceBadge = showSourceBadge,
        )
        val mediaContentDescription = title ?: effectivePost.id.sourcePostId
        val videoRef = remember(
            effectivePost.id.source,
            effectivePost.id.sourcePostId,
            previewPost.media,
            previewPost.full,
            previewPost.preview,
        ) {
            if (allowsInlineAutoplayInSearch(previewPost)) {
                resolveCardVideoRef(previewPost)
            } else {
                null
            }
        }
        var videoPlaybackFailed by remember(
            effectivePost.id.source,
            effectivePost.id.sourcePostId,
            videoRef?.url,
            videoRef?.localPath,
        ) {
            mutableStateOf(false)
        }
        val imageDeliveryPlan = remember(previewPost) { previewMediaDeliveryPlan(previewPost) }
        val imageCandidates = imageDeliveryPlan.candidates
        var displayedImageCandidateIndex by remember(
            effectivePost.id,
            imageDeliveryPlan,
        ) {
            mutableIntStateOf(0)
        }
        var maxPreparedImageCandidateIndex by remember(effectivePost.id, imageDeliveryPlan) {
            mutableIntStateOf(0)
        }
        var hasVisibleImage by remember(effectivePost.id, imageDeliveryPlan) { mutableStateOf(false) }
        var posterMemoryCacheKey by remember(effectivePost.id, imageDeliveryPlan) {
            mutableStateOf<MemoryCache.Key?>(null)
        }
        val previewCandidate = imageCandidates.getOrNull(displayedImageCandidateIndex)
        val previewUrl = previewCandidate?.location
        val ratio = remember(post.id, mediaAspectRatio) {
            mediaAspectRatio ?: previewAspectRatio(post)
        }
        val windowSize = LocalWindowInfo.current.containerSize
        val decodeSize = remember(windowSize.width, ratio) {
            feedPreviewDecodeSize(
                screenWidthPx = windowSize.width,
                aspectRatio = ratio,
            )
        }
        val imageModel = remember(
            context,
            previewUrl,
            effectivePost.id.source,
            decodeSize,
        ) {
            previewUrl?.let {
                buildFeedImageRequest(
                    context = context,
                    url = it,
                    sourceKey = effectivePost.id.source,
                    decodeSize = decodeSize,
                    // Animated drawables skip the memory cache, so keep the poster up while one decodes.
                    placeholderMemoryCacheKey = posterMemoryCacheKey.takeIf {
                        previewCandidate.activation == MediaDeliveryActivation.ANIMATION_UPGRADE
                    },
                )
            }
        }
        val mediaCount = postMediaCount(effectivePost)
        val pendingUpgrades = remember(imageDeliveryPlan, displayedImageCandidateIndex) {
            imageDeliveryPlan.upgradeIndicesAfter(displayedImageCandidateIndex)
        }
        val upgradesToAnimation = pendingUpgrades.firstOrNull()
            ?.let { index -> imageCandidates[index].activation == MediaDeliveryActivation.ANIMATION_UPGRADE } == true
        // Whole-file animation downloads are costly, so like video players they follow settled visibility.
        val upgradeWanted = !upgradesToAnimation || playbackActive

        LaunchedEffect(
            imageDeliveryPlan,
            displayedImageCandidateIndex,
            maxPreparedImageCandidateIndex,
            hasVisibleImage,
            decodeSize,
            upgradeWanted,
        ) {
            if (!hasVisibleImage || !upgradeWanted || displayedImageCandidateIndex != maxPreparedImageCandidateIndex) {
                return@LaunchedEffect
            }
            if (upgradesToAnimation) delay(FEED_PLAYER_ACTIVATION_DELAY_MS)
            pendingUpgrades.forEach { nextIndex ->
                val nextCandidate = imageCandidates[nextIndex]
                val result = runCatchingPreservingCancellation {
                    context.imageLoader.execute(
                        buildFeedImageRequest(
                            context = context,
                            url = nextCandidate.location,
                            sourceKey = effectivePost.id.source,
                            decodeSize = decodeSize,
                        )
                    )
                }.getOrNull()
                if (result is SuccessResult) {
                    maxPreparedImageCandidateIndex = nextIndex
                    displayedImageCandidateIndex = nextIndex
                    return@LaunchedEffect
                }
                if (
                    upgradesToAnimation &&
                    result is ErrorResult &&
                    canRecoverMedia(result.throwable, nextCandidate.location) &&
                    recoverCardMedia(nextCandidate.ref.copy(url = nextCandidate.location))
                ) {
                    return@LaunchedEffect
                }
            }
        }

        LaunchedEffect(effectivePost.id, videoRef?.url, videoRef?.localPath, resolvePostById) {
            if (videoRef == null && resolvedPostOverride == null) {
                requestResolvedCardPreview()
            }
        }
        // A visible caption must not retain autoplay demand for media outside the window.
        Box(
            modifier = Modifier
                .testTag(searchMediaTestTag(post.id))
                .fillMaxWidth()
                .aspectRatio(ratio)
                .onGloballyPositioned { coordinates ->
                    viewerMediaCoordinates = coordinates
                    mediaCorners = viewerReturnCorners(cardShape.createOutline(coordinates.size.toSize(), layoutDirection, cardDensity),
                        showTitle && supportingContent.showTitleFooter)
                    viewerReturn?.recordBounds(viewerReturnGrid, post.id, coordinates.boundsInWindow(clipBounds = false), viewerReturnShelf, mediaCorners)
                    val visible = coordinates.isAttached &&
                        isVisibleFeedBounds(coordinates.boundsInWindow(clipBounds = true))
                    if (isInViewport != visible) {
                        isInViewport = visible
                        onViewportChanged(visible)
                    }
                },
        ) {
            val showUgoira = isPixivUgoiraPost(effectivePost) && pixivUgoiraClient != null
            if (showUgoira) {
                PixivUgoiraPlayer(
                    postId = effectivePost.id.sourcePostId,
                    client = requireNotNull(pixivUgoiraClient),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = mediaContentDescription,
                    contentScale = ContentScale.Crop,
                    sizeBucket = UgoiraSizeBucket.CARD,
                    isActive = playbackActive,
                    onDurationKnown = onAuthoritativeDurationKnown,
                    loadingPreview = {
                        FeedAsyncImage(
                            model = imageModel,
                            contentDescription = mediaContentDescription,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            isActive = false,
                            onError = {
                                if (displayedImageCandidateIndex < imageCandidates.lastIndex) {
                                    displayedImageCandidateIndex += 1
                                }
                            },
                        )
                    },
                )
            } else if (videoRef != null && !videoPlaybackFailed) {
                SearchVideoPreview(
                    media = videoRef,
                    mediaIndex = postMediaItems(effectivePost).indexOf(videoRef).takeIf { it >= 0 }
                        ?: watchedPost?.let { postMediaItems(it).indexOf(videoRef).takeIf { index -> index >= 0 } }
                        ?: 0,
                    postId = effectivePost.id,
                    sourceKey = effectivePost.id.source,
                    playbackDiagnosticsEnabled = playbackDiagnosticsEnabled,
                    isActive = playbackActive,
                    previewModel = imageModel,
                    modifier = Modifier.fillMaxSize(),
                    onPlaybackError = {
                        videoPlaybackFailed = true
                        if (resolvedPostOverride == null) {
                            requestResolvedCardPreview(force = refreshOnPreviewError)
                        }
                    },
                    onDurationKnown = { durationMs ->
                        if (isAuthoritativeDurationMedia(effectivePost, videoRef)) {
                            onAuthoritativeDurationKnown(durationMs)
                        }
                    },
                )
            } else if (imageModel != null) {
                FeedAsyncImage(
                    model = imageModel,
                    contentDescription = mediaContentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    isActive = playbackActive,
                    onSuccess = { result ->
                        hasVisibleImage = true
                        maxPreparedImageCandidateIndex = displayedImageCandidateIndex
                        if (previewCandidate?.activation != MediaDeliveryActivation.ANIMATION_UPGRADE) {
                            posterMemoryCacheKey = result.memoryCacheKey
                        }
                    },
                    onError = { state ->
                        val canAdvance = !hasVisibleImage &&
                            displayedImageCandidateIndex < imageCandidates.lastIndex
                        val failedRef = previewCandidate?.ref?.copy(url = previewUrl)
                        if (failedRef != null && canRecoverMedia(state.result.throwable, previewUrl)) {
                            scope.launch {
                                if (!recoverCardMedia(failedRef) && canAdvance) {
                                    displayedImageCandidateIndex += 1
                                }
                            }
                        } else if (canAdvance) {
                            displayedImageCandidateIndex += 1
                        } else if (resolvedPostOverride == null && refreshOnPreviewError) {
                            requestResolvedCardPreview(force = true)
                        }
                    },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No preview",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (mediaCount > 1) {
                com.theoriacodex.app.ui.components.MediaCountBadge(
                    text = imageCountBadgeLabel(mediaCount, cardViewedMediaNumber),
                    description = if (cardViewedMediaNumber == null) "Image count" else "Watched media progress",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            if (onToggleLike != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (liked) "Unlike post" else "Like post"
                            selected = liked
                            stateDescription = if (liked) "Liked" else "Not liked"
                        }
                        .clickable(
                            role = Role.Checkbox,
                            onClick = onToggleLike,
                        ),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("Search like visual"),
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (liked) {
                                    Icons.Default.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = null,
                                tint = if (liked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.White
                                },
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            if (supportingContent.showPreviewContext) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (metadataLabel != null) {
                        PreviewMetadataLabel(metadataLabel)
                    }
                    if (watchedDescription != null) {
                        PreviewWatchedBadge(watchedDescription)
                    }
                    if (showSourceBadge) {
                        SourceBadge(source = effectivePost.id.source)
                    }
                }
            }
            if (durationLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                ) {
                    PreviewMetadataLabel(durationLabel)
                }
            }
        }

        if (showTitle && supportingContent.showTitleFooter) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = requireNotNull(title),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class SearchCardSupportingContent(
    val showTitleFooter: Boolean,
    val showPreviewContext: Boolean,
)

internal fun searchCardSupportingContent(
    title: String?,
    metadataLabel: String?,
    showWatchedBadge: Boolean,
    showSourceBadge: Boolean,
): SearchCardSupportingContent {
    return SearchCardSupportingContent(
        showTitleFooter = title != null,
        showPreviewContext = metadataLabel != null || showWatchedBadge || showSourceBadge,
    )
}

@Composable
private fun PreviewMetadataLabel(label: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Marks a previously watched post; when it was watched is left to screen readers and Recents. */
@Composable
private fun PreviewWatchedBadge(description: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.padding(4.dp).size(14.dp),
        )
    }
}

internal fun imageCountBadgeLabel(count: Int, viewedMediaNumber: Int?): String {
    val progress = viewedMediaNumber?.coerceIn(1, count)
    return progress?.let { "$it/$count" } ?: count.toString()
}

internal fun postMediaCount(post: Post): Int {
    post.mediaCount?.takeIf { it > 0 }?.let { return it }
    val explicitCount = post.media.count { !it.url.isNullOrBlank() || !it.localPath.isNullOrBlank() }
    return when {
        explicitCount > 0 -> explicitCount
        post.full != null -> 1
        else -> 1
    }
}

private fun resolveCardVideoRef(post: Post): ImageRef? {
    return postPlaybackMediaCandidate(post)?.ref
}

internal fun allowsInlineAutoplayInSearch(post: Post): Boolean {
    return post.id.source != SourceKey.IWARA && post.id.source != SourceKey.HITOMI
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

@Composable
private fun SourceBadge(
    source: SourceKey,
    modifier: Modifier = Modifier,
) {
    val labelHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.lineHeight.toDp()
    }
    Surface(
        modifier = modifier.height(labelHeight + 8.dp),
        color = if (source.isRule34Family()) {
            Color.Transparent
        } else {
            Color.Black.copy(alpha = 0.55f)
        },
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            SourceLogo(
                source = source,
                size = if (source.isRule34Family()) 0.dp else 12.dp,
            )
        }
    }
}

internal fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}
