package com.theoriacodex.app.recommend

import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.motion.depthTransform
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEmptyReason
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchDurationFilterPresentation
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.search.rememberSearchDurationFilterPresentation
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedScrollSignal
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: ForYouUiState,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    onDurationFilterChanged: (Boolean) -> Unit = {},
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onToggleLike: (Post) -> Unit,
    onAction: (ForYouAction) -> Unit,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean,
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: () -> Unit,
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
) {
    BackHandler(enabled = !state.home.visible) { onAction(ForYouAction.ShowHome) }
    val savedStates = rememberSaveableStateHolder()
    val gridState = rememberLazyStaggeredGridState()
    ScrollToTopOnTabReselect(TopLevelDestination.ForYou) {
        if (!state.home.visible) gridState.animateScrollToItem(0)
    }
    ForYouSeedPositionEffect(state.seedId, gridState)
    var showSortSheet by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    val feed = rememberForYouFeed(state, fabRestoreState, durationStates, resolveUnknownAnimatedDurations)
    LaunchedEffect(feed.durationFilter.active) { onDurationFilterChanged(feed.durationFilter.active) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val filterScroll = rememberFeedScrollSignal()
    val filterLabels = forYouFilterLabels(state.sortMode, feed)
    val clearFilters: () -> () -> Unit = {
        clearForYouFilters(state.sortMode, fabRestoreState, onFabRestoreStateChange, onAction)
    }
    val pageDemand = rememberForYouPageDemand(state, feed, gridState, onAction)
    val cards = rememberFeedCardEnvironment(
        pixivUgoiraClient, likedPostIds, feed.durationFilter.knownDurationMsByPostId, durationStateForPost,
        onToggleLike, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown,
    )

    AnimatedContent(
        targetState = state.home.visible,
        transitionSpec = { depthTransform(forward = !targetState) },
        label = "For You view",
    ) { homeVisible ->
        if (homeVisible) {
            savedStates.SaveableStateProvider("overview") {
                ForYouHomeScreen(state, likedPostIds, pixivUgoiraClient, feed::filterHomePosts,
                    durationStateForPost, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike,
                    { selectedActionPost = it }, onAction, { showSortSheet = true }, filterLabels, clearFilters,
                    onOpenPreview = { id, posts, index -> onAction(ForYouAction.OpenSectionResult(id, index, posts, feed.visibilityFilters)) })
            }
        } else {
            ForYouResults(
                state, feed, cards, gridState, pageDemand, filterScroll, filterLabels, clearFilters, showSourceMenu,
                onSourceMenuChange = { showSourceMenu = it }, onShowFilters = { showSortSheet = true },
                onAction = onAction, onLongPress = { selectedActionPost = it },
            )
        }
    }

    selectedActionPost?.let { post ->
        FeedPostActionSheet(
            post, creatorBrowsingSources, { selectedActionPost = null }, onSaveToDevice, onRequestSaveToCodex, onOpenCreatorProfile,
            onOpenLegacyCreatorProfile, onGoToSearch, onPostUrlCopied, onAddIncludeTerm, onAddExcludeTerm, onRemoveIncludeTerm,
            onRemoveExcludeTerm, onFavoriteTagLongPress,
        )
    }

    if (showSortSheet) {
        ForYouSortSheet(
            state.sortMode, fabRestoreState, feed, onFabRestoreStateChange, onAction,
            onReset = { clearFilters() }, onDismiss = { showSortSheet = false },
        )
    }
}

/** A new seed starts from the top; the position saved with the current seed survives recreation. */
@Composable
private fun ForYouSeedPositionEffect(seedId: String, gridState: LazyStaggeredGridState) {
    var positionedSeed by rememberSaveable { mutableStateOf(seedId) }
    LaunchedEffect(seedId) {
        if (positionedSeed != seedId) {
            gridState.scrollToItem(0)
            positionedSeed = seedId
        }
    }
}

private fun forYouFilterLabels(sortMode: SortMode, feed: ForYouFeed): List<String> = listOfNotNull(
    sortModeLabel(sortMode).takeIf { sortMode != SortMode.NEWEST },
    "Animated".takeIf { feed.animatedOnly },
    animatedDurationRangeLabel(feed.animatedDurationRange).takeIf { feed.durationFilter.active },
)

@Composable
private fun ForYouResults(
    state: ForYouUiState,
    feed: ForYouFeed,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    filterScroll: FeedScrollSignal,
    filterLabels: List<String>,
    clearFilters: () -> () -> Unit,
    showSourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit,
    onShowFilters: () -> Unit,
    onAction: (ForYouAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                labels = filterLabels,
                contentDescription = "Filter and sort recommendations",
                onClick = onShowFilters,
                onClear = clearFilters,
                scrollSignal = filterScroll,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ForYouResultsHeader(state, onAction)
            ForYouSourceSelector(
                selectedSource = state.selectedSource,
                availableSources = state.availableSources,
                enabled = !state.isRefreshing && !state.isPaging,
                expanded = showSourceMenu,
                onExpandedChange = onSourceMenuChange,
                onSourceSelected = { source ->
                    onSourceMenuChange(false)
                    onAction(ForYouAction.SelectSource(source))
                },
            )
            ForYouSeedSummary(state)
            com.theoriacodex.app.search.StatusRow(
                statuses = state.statuses.filter { it.source in state.retryableSources },
                retryEnabled = !state.isRefreshing && !state.isPaging,
                onRetrySource = { onAction(ForYouAction.RetrySource(it)) },
            )
            FeedContinueLoading(pageDemand)
            ForYouFeedBody(state, feed, cards, gridState, onAction, onLongPress)
        }
    }
}

/** What the For You grid shows after its filters, durations, and related posts are applied. */
private data class ForYouFeed(
    val animatedOnly: Boolean,
    val animatedDurationRange: AnimatedDurationRange,
    val visibilityFilters: SearchVisibilityFilters,
    val durationFilter: SearchDurationFilterPresentation,
    val visibleResults: List<Post>,
    val related: RelatedFeedPresentation,
) {
    /** Home sections ignore likes and saves, so they keep showing what trained them. */
    fun filterHomePosts(posts: List<Post>): List<Post> = filterSearchResults(
        posts, visibilityFilters, emptySet(), emptySet(),
        unknownAnimatedDurationPolicy = durationFilter.unknownPolicy,
        knownDurationMsByPostId = durationFilter.knownDurationMsByPostId,
    )
}

@Composable
private fun rememberForYouFeed(
    state: ForYouUiState,
    fabRestoreState: FeedFabRestoreState,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
): ForYouFeed {
    val animatedOnly = fabRestoreState.animatedOnly
    val durationMinBucket = fabRestoreState.durationMinBucket
    val durationMaxBucket = fabRestoreState.durationMaxBucket
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket)
    }
    val visibilityFilters = remember(animatedOnly, animatedDurationRange) {
        SearchVisibilityFilters(animatedOnly = animatedOnly, animatedDurationRange = animatedDurationRange)
    }
    val durationPosts = if (state.home.visible) {
        state.home.sections.flatMap { it.feed.page.items } + state.home.mixedFeed?.page?.items.orEmpty()
    } else state.results
    val durationFilter = rememberSearchDurationFilterPresentation(
        durationPosts, animatedDurationRange, durationStates, resolveUnknownAnimatedDurations,
    )
    val visibleResults = remember(
        state.results,
        visibilityFilters,
        durationFilter.unknownPolicy,
        durationFilter.knownDurationMsByPostId,
    ) {
        filterSearchResults(
            results = state.results,
            filters = visibilityFilters,
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = durationFilter.unknownPolicy,
            knownDurationMsByPostId = durationFilter.knownDurationMsByPostId,
        )
    }
    val related = rememberRelatedFeedPresentation(
        canonicalPosts = state.results,
        visibleCanonicalPosts = visibleResults,
        relatedState = state.relatedPosts,
        visibilityFilters = visibilityFilters,
        likedPostIds = emptySet(),
        savedPostIds = emptySet(),
        watchedPostIds = emptySet(),
        unknownAnimatedDurationPolicy = durationFilter.unknownPolicy,
        durationStates = durationStates,
        durationFilterActive = durationFilter.active,
    )
    return remember(animatedOnly, animatedDurationRange, visibilityFilters, durationFilter, visibleResults, related) {
        ForYouFeed(animatedOnly, animatedDurationRange, visibilityFilters, durationFilter, visibleResults, related)
    }
}

@Composable
private fun rememberForYouPageDemand(
    state: ForYouUiState,
    feed: ForYouFeed,
    gridState: LazyStaggeredGridState,
    onAction: (ForYouAction) -> Unit,
) = rememberFeedPageDemand(
    input = FeedPageDemandInput(
        contextKey = "${state.activeProfileId}:${state.selectedSource}:${state.sortMode}:${state.seedId}",
        completedGeneration = state.nextRequestGeneration,
        canonicalCount = state.results.size,
        visibleCount = feed.visibleResults.size,
        canLoadMore = state.canLoadMore,
        ready = !state.home.visible && state.seedSummaryBySource.isNotEmpty(),
        refreshing = state.isRefreshing,
        paging = state.isPaging,
        failed = state.errorMessage != null,
        resolvingDurations = feed.durationFilter.readiness.isResolving,
    ),
    filters = feed.visibilityFilters,
    gridState = gridState,
    presentedItemCount = feed.related.projection.entries.size,
    canonicalIndexForVisibleItems = feed.related.projection::greatestVisibleCanonicalIndex,
    onLoadNextPage = { onAction(ForYouAction.LoadNextPage) },
)

/** Clears the feed's filters and returns how to put them back. */
private fun clearForYouFilters(
    previousSort: SortMode,
    previous: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onAction: (ForYouAction) -> Unit,
): () -> Unit {
    onFabRestoreStateChange(
        previous.copy(
            sortMode = SortMode.NEWEST.name,
            animatedOnly = false,
            durationMinBucket = AnimatedDurationRange.Full.normalizedMinBucket,
            durationMaxBucket = AnimatedDurationRange.Full.normalizedMaxBucket,
        ),
    )
    onAction(ForYouAction.SelectSort(SortMode.NEWEST))
    return {
        onFabRestoreStateChange(previous)
        onAction(ForYouAction.SelectSort(previousSort))
    }
}

@Composable
private fun ForYouResultsHeader(state: ForYouUiState, onAction: (ForYouAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAction(ForYouAction.ShowHome) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to interests")
            }
            Text(state.home.activeSection?.title ?: "For You", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
                enabled = state.canBlacklistCurrentSeed,
                onClick = { onAction(ForYouAction.BlacklistCurrentSeed) },
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Blacklist current recommendation tags",
                )
            }
            IconButton(
                enabled = state.canRefresh,
                onClick = { onAction(ForYouAction.Refresh(shuffle = true)) },
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = if (state.home.activeSection == null) "Shuffle recommendations" else "Refresh this interest",
                )
            }
        }
    }
}

@Composable
private fun ForYouSeedSummary(state: ForYouUiState) {
    if (state.seedSummaryBySource.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.seedSummaryBySource.entries.toList()) { (source, tags) ->
            Card {
                Text(
                    text = "${source.displayName()}: ${tags.joinToString(" + ")}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ForYouFeedBody(
    state: ForYouUiState,
    feed: ForYouFeed,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    onAction: (ForYouAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    when {
        state.emptyReason == ForYouEmptyReason.NO_LIKES -> ForYouNoLikes(onAction)

        state.isRefreshing && feed.visibleResults.isEmpty() -> FeedLoadingState()

        state.errorMessage != null && feed.visibleResults.isEmpty() -> {
            FeedErrorTile(
                message = state.errorMessage.orEmpty(),
                onRetry = { onAction(ForYouAction.Refresh(shuffle = true)) },
            )
        }

        feed.related.projection.entries.isEmpty() -> FeedEmptyTile(message = forYouEmptyMessage(state, feed))

        else -> {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onAction(ForYouAction.Refresh(shuffle = false)) },
                modifier = Modifier.fillMaxSize(),
            ) {
                TwoColumnProjectedPostStaggeredGrid(
                    projection = feed.related.projection,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    showPagingTile = state.isPaging,
                    footerMessage = if (feed.durationFilter.readiness.isResolving) "Resolving durations…" else null,
                    postContent = { _, post ->
                        FeedPostCard(
                            post = post,
                            environment = cards,
                            showWatchHistory = true,
                            showSourceBadge = true,
                            onClick = {
                                onAction(
                                    ForYouAction.OpenResult(
                                        index = feed.visibleResults.indexOfFirst { candidate -> candidate.id == post.id },
                                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        visibleResults = feed.visibleResults,
                                        visibilityFilters = feed.visibilityFilters,
                                    )
                                )
                            },
                            onLongPress = { onLongPress(post) },
                        )
                    },
                    shelfContent = {
                        FeedRelatedPostsShelf(
                            state = state.relatedPosts,
                            presentation = feed.related,
                            environment = cards,
                            onOpenPost = { posts, index -> onAction(ForYouAction.OpenRelatedResult(index, posts)) },
                            onLongPress = onLongPress,
                            onDismiss = { onAction(ForYouAction.DismissRelatedPosts) },
                            onRetry = { onAction(ForYouAction.RetryRelatedPosts) },
                            onPreviousPage = { onAction(ForYouAction.ShowPreviousRelatedPosts) },
                            onNextPage = { onAction(ForYouAction.ShowNextRelatedPosts) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ForYouNoLikes(onAction: (ForYouAction) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Heart posts in Search to train For You.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onAction(ForYouAction.GoToSearch) }) {
                Text("Go to Search")
            }
        }
    }
}

private fun forYouEmptyMessage(state: ForYouUiState, feed: ForYouFeed): String = when {
    feed.durationFilter.readiness.isResolving -> "Resolving durations…"
    !(feed.animatedOnly || feed.durationFilter.active) || state.results.isEmpty() ->
        "No recommendations yet. Tap Refresh to try a broader seed."
    !feed.visibilityFilters.animatedDurationRange.isFullRange ->
        "No animated media found in the selected duration range."
    else -> "No animated media found for this feed yet."
}

@Composable
internal fun ForYouSourceSelector(
    selectedSource: SourceKey?,
    availableSources: List<SourceKey>,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSourceSelected: (SourceKey?) -> Unit,
) {
    SourceSelector(
        selectedSource = selectedSource,
        availableSources = availableSources,
        allSourcesLabel = "Unified",
        accessibilityLabel = "Select For You source",
        enabled = enabled,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onSourceSelected = onSourceSelected,
        minimumHeight = 33.dp,
        verticalPadding = 1.dp,
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForYouSortSheet(
    selectedSort: SortMode,
    fabRestoreState: FeedFabRestoreState,
    feed: ForYouFeed,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onAction: (ForYouAction) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset) {
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), selectedSort == mode) {
                    onFabRestoreStateChange(fabRestoreState.copy(sortMode = mode.name))
                    onAction(ForYouAction.SelectSort(mode))
                }
            }
        }
        FilterSection("Show") {
            FilterChoiceChip("Animated only", feed.animatedOnly) {
                onFabRestoreStateChange(fabRestoreState.copy(animatedOnly = !feed.animatedOnly))
            }
        }
        AnimatedDurationRangeControl(
            range = feed.animatedDurationRange,
            onRangeChange = { range ->
                onFabRestoreStateChange(
                    fabRestoreState.copy(
                        durationMinBucket = range.normalizedMinBucket,
                        durationMaxBucket = range.normalizedMaxBucket,
                    ),
                )
            },
        )
    }
}
