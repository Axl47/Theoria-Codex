package com.theoriacodex.app.creator

import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.FeedContinueLoading
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.copyTextToClipboard
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchDurationFilterPresentation
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.search.rememberSearchDurationFilterPresentation
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostBoundTagSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorProfileScreen(
    state: CreatorUiState,
    followed: Boolean = false,
    onToggleFollow: () -> Unit = {},
    likedPostIds: Set<PostId>,
    pendingLikePostIds: Set<PostId> = emptySet(),
    savedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    onDurationFilterChanged: (Boolean) -> Unit = {},
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onToggleLike: (Post) -> Unit,
    onAction: (CreatorAction) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenUrl: (String) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
) {
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()
    val feed = rememberCreatorFeed(state, pendingLikePostIds, likedPostIds, savedPostIds, fabRestoreState, durationStates, resolveUnknownAnimatedDurations)
    LaunchedEffect(feed.durationFilter.active) { onDurationFilterChanged(feed.durationFilter.active) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val pageDemand = rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = state.queryHash.orEmpty(),
            completedGeneration = state.nextRequestGeneration,
            canonicalCount = state.results.size,
            visibleCount = feed.visibleResults.size,
            canLoadMore = state.canLoadMore,
            ready = state.creator != null && state.queryHash != null,
            refreshing = state.isRefreshing,
            paging = state.isPaging,
            failed = state.errorMessage != null,
            resolvingDurations = feed.durationFilter.readiness.isResolving,
        ),
        filters = feed.visibilityFilters,
        gridState = gridState,
        canonicalIndexForVisibleItems = feed.related.projection::greatestVisibleCanonicalIndex,
        onLoadNextPage = { onAction(CreatorAction.LoadNextPage) },
    )
    val creator = state.creator
    if (creator == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No creator selected")
        }
        return
    }
    val cards = rememberFeedCardEnvironment(
        pixivUgoiraClient, likedPostIds, feed.durationFilter.knownDurationMsByPostId, durationStateForPost,
        onToggleLike, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown,
    )
    val filterScroll = rememberFeedScrollSignal()
    val filterLabels = listOfNotNull(
        "Animated".takeIf { fabRestoreState.animatedOnly },
        animatedDurationRangeLabel(feed.animatedDurationRange).takeIf { feed.durationFilter.active },
        "Hide liked".takeIf { fabRestoreState.hideLiked },
        "Hide saved".takeIf { fabRestoreState.hideSaved },
    )
    val clearFilters: () -> () -> Unit = { clearCreatorFilters(fabRestoreState, onFabRestoreStateChange) }
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                labels = filterLabels,
                contentDescription = "Filter creator uploads",
                onClick = { showFilterSheet = true },
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
            CreatorAppBar(creator, followed, !state.isRefreshing, onToggleFollow, onOpenUrl, onBack = { onAction(CreatorAction.Back) })
            if (state.errorMessage != null && state.canLoadMore) {
                FeedErrorTile(
                    message = state.errorMessage.orEmpty(),
                    onRetry = { onAction(CreatorAction.RetryPage) },
                )
            }
            FeedContinueLoading(pageDemand)
            CreatorFeedBody(state, feed, cards, gridState, onAction, onLongPress = { selectedActionPost = it })
        }
    }

    if (showFilterSheet) {
        CreatorFilterSheet(
            fabRestoreState, feed.animatedDurationRange, onFabRestoreStateChange,
            onReset = { clearFilters() }, onDismiss = { showFilterSheet = false },
        )
    }

    selectedActionPost?.let { post ->
        PostActionSheet(
            post = post,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                PostBoundTagSection(post, onAddIncludeTerm, onAddExcludeTerm, onRemoveIncludeTerm, onRemoveExcludeTerm)
            },
        )
    }
}

/** What the creator's grid shows after its filters, durations, and related posts are applied. */
private data class CreatorFeed(
    val animatedDurationRange: AnimatedDurationRange,
    val visibilityFilters: SearchVisibilityFilters,
    val durationFilter: SearchDurationFilterPresentation,
    val visibleResults: List<Post>,
    val related: RelatedFeedPresentation,
)

@Composable
private fun rememberCreatorFeed(
    state: CreatorUiState,
    pendingLikePostIds: Set<PostId>,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    fabRestoreState: FeedFabRestoreState,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
): CreatorFeed {
    val animatedOnly = fabRestoreState.animatedOnly
    val hideLiked = fabRestoreState.hideLiked
    val hideSaved = fabRestoreState.hideSaved
    val durationMinBucket = fabRestoreState.durationMinBucket
    val durationMaxBucket = fabRestoreState.durationMaxBucket
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket)
    }
    val visibilityFilters = remember(animatedOnly, hideLiked, hideSaved, animatedDurationRange) {
        SearchVisibilityFilters(
            animatedOnly = animatedOnly,
            hideLiked = hideLiked,
            hideSaved = hideSaved,
            animatedDurationRange = animatedDurationRange,
        )
    }
    val durationFilter = rememberSearchDurationFilterPresentation(
        state.results, animatedDurationRange, durationStates, resolveUnknownAnimatedDurations,
    )
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(state.relatedPosts, pendingLikePostIds) {
        state.relatedPosts.retainedLikedPostIds + pendingLikePostIds
    })
    val visibleResults = remember(
        state.results,
        visibilityFilters,
        likedPostIds,
        retainedLikedPostIds,
        savedPostIds,
        durationFilter.unknownPolicy,
        durationFilter.knownDurationMsByPostId,
    ) {
        filterSearchResults(
            results = state.results,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            retainedLikedPostIds = retainedLikedPostIds,
            savedPostIds = savedPostIds,
            unknownAnimatedDurationPolicy = durationFilter.unknownPolicy,
            knownDurationMsByPostId = durationFilter.knownDurationMsByPostId,
        )
    }
    val related = rememberRelatedFeedPresentation(
        pendingLikePostIds = pendingLikePostIds,
        canonicalPosts = state.results,
        visibleCanonicalPosts = visibleResults,
        relatedState = state.relatedPosts,
        visibilityFilters = visibilityFilters,
        likedPostIds = likedPostIds,
        savedPostIds = savedPostIds,
        watchedPostIds = emptySet(),
        unknownAnimatedDurationPolicy = durationFilter.unknownPolicy,
        durationStates = durationStates,
        durationFilterActive = durationFilter.active,
    )
    return remember(animatedDurationRange, visibilityFilters, durationFilter, visibleResults, related) {
        CreatorFeed(animatedDurationRange, visibilityFilters, durationFilter, visibleResults, related)
    }
}

/** Clears the feed's filters and returns how to put them back. */
private fun clearCreatorFilters(
    previous: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
): () -> Unit {
    onFabRestoreStateChange(
        previous.copy(
            animatedOnly = false,
            hideLiked = false,
            hideSaved = false,
            durationMinBucket = AnimatedDurationRange.Full.normalizedMinBucket,
            durationMaxBucket = AnimatedDurationRange.Full.normalizedMaxBucket,
        ),
    )
    return { onFabRestoreStateChange(previous) }
}

@Composable
private fun CreatorAppBar(
    creator: CreatorProfile,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    SecondaryScreenAppBar(
        title = creator.displayName,
        subtitle = creator.source.displayName(),
        onBack = onBack,
    ) {
        CreatorFollowButton(followed = followed, onClick = onToggleFollow, enabled = followEnabled)
        creator.profileUrl?.takeIf { it.isNotBlank() }?.let { profileUrl ->
            IconButton(onClick = { onOpenUrl(profileUrl) }) {
                Icon(
                    imageVector = Icons.Default.OpenInBrowser,
                    contentDescription = "Open creator profile in browser",
                )
            }
            CreatorShareMenu(profileUrl)
        }
    }
}

@Composable
private fun CreatorShareMenu(profileUrl: String) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Default.Share, contentDescription = "Share creator profile")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Share link") },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    shareCreatorProfile(context = context, profileUrl = profileUrl)
                },
            )
            DropdownMenuItem(
                text = { Text("Copy link") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    copyCreatorProfile(context = context, profileUrl = profileUrl)
                },
            )
        }
    }
}

@Composable
private fun CreatorFeedBody(
    state: CreatorUiState,
    feed: CreatorFeed,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    onAction: (CreatorAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    when {
        state.isRefreshing && feed.visibleResults.isEmpty() -> FeedLoadingState()

        state.errorMessage != null && !state.canLoadMore && feed.visibleResults.isEmpty() -> {
            FeedErrorTile(
                message = state.errorMessage.orEmpty(),
                onRetry = { onAction(CreatorAction.Refresh) },
            )
        }

        feed.visibleResults.isEmpty() && state.relatedPosts == RelatedPostsUiState.Idle -> {
            FeedEmptyTile(message = creatorEmptyMessage(state, feed))
        }

        else -> CreatorGrid(state, feed, cards, gridState, onAction, onLongPress)
    }
}

@Composable
private fun CreatorGrid(
    state: CreatorUiState,
    feed: CreatorFeed,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    onAction: (CreatorAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    val visibleIndexById = remember(feed.visibleResults) {
        feed.visibleResults.withIndex().associate { it.value.id to it.index }
    }
    TwoColumnProjectedPostStaggeredGrid(
        projection = feed.related.projection,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        footerMessage = if (feed.durationFilter.readiness.isResolving) "Resolving durations…" else null,
        postContent = { _, post ->
            FeedPostCard(
                post = post,
                environment = cards,
                onClick = {
                    onAction(
                        CreatorAction.OpenResult(
                            index = visibleIndexById.getValue(post.id),
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
                onOpenPost = { posts, index -> onAction(CreatorAction.OpenRelatedResult(index, posts)) },
                onLongPress = onLongPress,
                onDismiss = { onAction(CreatorAction.DismissRelatedPosts) },
                onRetry = { onAction(CreatorAction.RetryRelatedPosts) },
                onPreviousPage = { onAction(CreatorAction.ShowPreviousRelatedPosts) },
                onNextPage = { onAction(CreatorAction.ShowNextRelatedPosts) },
            )
        },
    )
}

private fun creatorEmptyMessage(state: CreatorUiState, feed: CreatorFeed): String = when {
    feed.durationFilter.readiness.isResolving -> "Resolving durations…"
    state.results.isEmpty() -> "No uploads found for this creator."
    !feed.visibilityFilters.animatedDurationRange.isFullRange ->
        "No animated media found in the selected duration range."
    else -> "No uploads match the current filters."
}

@Composable
private fun CreatorFilterSheet(
    fabRestoreState: FeedFabRestoreState,
    animatedDurationRange: AnimatedDurationRange,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset) {
        FilterSection("Show") {
            FilterChoiceChip("Animated only", fabRestoreState.animatedOnly) {
                onFabRestoreStateChange(fabRestoreState.copy(animatedOnly = !fabRestoreState.animatedOnly))
            }
            FilterChoiceChip("Hide liked", fabRestoreState.hideLiked) {
                onFabRestoreStateChange(fabRestoreState.copy(hideLiked = !fabRestoreState.hideLiked))
            }
            FilterChoiceChip("Hide saved", fabRestoreState.hideSaved) {
                onFabRestoreStateChange(fabRestoreState.copy(hideSaved = !fabRestoreState.hideSaved))
            }
        }
        AnimatedDurationRangeControl(
            range = animatedDurationRange,
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

private fun shareCreatorProfile(context: Context, profileUrl: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, profileUrl)
    }
    context.startActivity(Intent.createChooser(intent, "Share creator profile"))
}

private fun copyCreatorProfile(context: Context, profileUrl: String) {
    if (!copyTextToClipboard(context, "Creator profile", profileUrl)) {
        Toast.makeText(context, "Could not copy creator link", Toast.LENGTH_SHORT).show()
    }
}
