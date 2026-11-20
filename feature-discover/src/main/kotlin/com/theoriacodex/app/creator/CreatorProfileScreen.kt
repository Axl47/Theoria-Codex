package com.theoriacodex.app.creator

import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import com.theoriacodex.app.search.markInputs
import com.theoriacodex.app.search.rememberFilteredFeed
import com.theoriacodex.app.search.rememberSearchDurationFilterPresentation
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostBoundTagSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.DismissibleFailure
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.pagedFeedFooterMessage
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
import com.theoriacodex.domain.model.AccountFeed
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
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    onToggleLike: (Post) -> Unit,
    onAction: (CreatorAction) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
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
    // Each feed keeps its own scroll position and duration reveal order.
    val gridState = rememberRetainedFeedGridState(state.selectedFeed.retainKey)
    val feed = rememberCreatorFeed(state, pendingLikePostIds, likedPostIds, savedPostIds, fabRestoreState, durationStates, resolveUnknownAnimatedDurations)
    LaunchedEffect(feed.durationFilter.active) { onDurationFilterChanged(feed.durationFilter.active) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    val pageDemand = rememberCreatorPageDemand(state, feed, gridState, onAction)
    val creator = state.creator
    if (creator == null) {
        CreatorNotSelected()
        return
    }
    val cards = rememberFeedCardEnvironment(
        pixivUgoiraClient, likedPostIds, feed.durationFilter.knownDurationMsByPostId, durationStateForPost,
        onToggleLike, onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, resolvePostById,
    )
    val filterScroll = rememberFeedScrollSignal()
    val clearFilters: () -> () -> Unit = { clearCreatorFilters(fabRestoreState, onFabRestoreStateChange) }
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                labels = creatorFilterLabels(fabRestoreState, feed),
                contentDescription = "Filter creator ${state.selectedFeed.noun}",
                onClick = { showFilterSheet = true },
                onClear = clearFilters,
                scrollSignal = filterScroll,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val account = state.feeds.isNotEmpty()
            val followEnabled = !state.uploadsRefreshing
            CreatorHeader(state, creator, account, followed, followEnabled, onToggleFollow, onOpenUrl, onAction)
            CreatorFeedBody(state, feed, cards, gridState, pageDemand, onAction, onLongPress = { selectedActionPost = it })
        }
    }
    if (showFilterSheet) {
        CreatorFilterSheet(
            fabRestoreState, feed.animatedDurationRange, onFabRestoreStateChange,
            // The sheet reset has no undo affordance, so the undo callback is intentionally discarded.
            onReset = @Suppress("IgnoredReturnValue") { clearFilters() }, onDismiss = { showFilterSheet = false },
        )
    }
    selectedActionPost?.let { post ->
        PostActionSheet(
            post, onDismiss = { selectedActionPost = null }, onSaveToDevice = { onSaveToDevice(post) },
            liked = post.id in likedPostIds, onToggleLike = { onToggleLike(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onShowRelated = { onAction(CreatorAction.ShowRelatedPosts(post)) },
        ) { PostBoundTagSection(post, onAddIncludeTerm, onAddExcludeTerm, onRemoveIncludeTerm, onRemoveExcludeTerm) }
    }
}

@Composable
private fun rememberCreatorPageDemand(
    state: CreatorUiState,
    feed: CreatorFeed,
    gridState: LazyStaggeredGridState,
    onAction: (CreatorAction) -> Unit,
) = rememberFeedPageDemand(
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
        durationsBlockPaging = feed.durationFilter.blocksPaging,
    ),
    filters = feed.visibilityFilters,
    gridState = gridState,
    canonicalIndexForVisibleItems = feed.related.projection::greatestVisibleCanonicalIndex,
    onLoadNextPage = { onAction(CreatorAction.LoadNextPage) },
)

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
        state.results, animatedDurationRange, durationStates, resolveUnknownAnimatedDurations, retainKey = state.selectedFeed.retainKey,
    )
    val presentedResults = durationFilter.presentedPosts ?: state.results
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(state.relatedPosts, pendingLikePostIds) {
        state.relatedPosts.retainedLikedPostIds + pendingLikePostIds
    })
    val marks = visibilityFilters.markInputs(likedPostIds, savedPostIds, emptySet(), retainedLikedPostIds)
    val visibleResults = rememberFilteredFeed(
        presentedResults, visibilityFilters, durationFilter.unknownPolicy, durationFilter.metadata, marks,
        retainKey = state.selectedFeed.retainKey,
    )
    val related = rememberRelatedFeedPresentation(
        retainKey = state.selectedFeed.retainKey,
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

@Composable
private fun CreatorNotSelected() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No creator selected")
    }
}

private fun creatorFilterLabels(fabRestoreState: FeedFabRestoreState, feed: CreatorFeed): List<String> = listOfNotNull(
    "Animated".takeIf { fabRestoreState.animatedOnly },
    animatedDurationRangeLabel(feed.animatedDurationRange).takeIf { feed.durationFilter.active },
    "Hide liked".takeIf { fabRestoreState.hideLiked },
    "Hide saved".takeIf { fabRestoreState.hideSaved },
)

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

/** The bar and, for accounts, their facts and feed tabs; landscape accounts fold all of it into one bar. */
@Composable
private fun CreatorHeader(
    state: CreatorUiState,
    creator: CreatorProfile,
    account: Boolean,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAction: (CreatorAction) -> Unit,
) {
    val onBack = { onAction(CreatorAction.Back) }
    if (account && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        CompactAccountBar(state, creator, followed, followEnabled, onToggleFollow, onOpenUrl, onBack, onAction)
    } else {
        CreatorAppBar(creator, account, followed, followEnabled, onToggleFollow, onOpenUrl, onBack)
        if (account) AccountSection(state, creator, followed, followEnabled, onToggleFollow, onAction)
    }
}

/** An account's facts and its feed tabs, below the app bar. */
@Composable
private fun AccountSection(
    state: CreatorUiState,
    creator: CreatorProfile,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onAction: (CreatorAction) -> Unit,
) {
    AccountHeader(creator, state.details, followed, followEnabled, onToggleFollow)
    AccountFeedTabs(state.tabs, state.selectedFeed) { onAction(CreatorAction.SelectFeed(it)) }
}

/** Account pages move Follow and the source name into the header below the bar. */
@Composable
private fun CreatorAppBar(
    creator: CreatorProfile,
    account: Boolean,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    SecondaryScreenAppBar(
        title = creator.displayName,
        subtitle = creator.source.displayName().takeUnless { account },
        onBack = onBack,
    ) {
        if (!account) CreatorFollowButton(followed = followed, onClick = onToggleFollow, enabled = followEnabled)
        CreatorLinkActions(creator, onOpenUrl)
    }
}

/** Landscape account pages fold the header and feed tabs into one bar, with the bio behind a toggle. */
@Composable
private fun CompactAccountBar(
    state: CreatorUiState,
    creator: CreatorProfile,
    followed: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    onAction: (CreatorAction) -> Unit,
) {
    var bioShown by rememberSaveable(creator) { mutableStateOf(false) }
    val bio = state.details.shownBio
    SecondaryScreenAppBar(
        title = creator.displayName,
        subtitle = remember(creator.source, state.details) { accountHeaderText(creator.source, state.details).line },
        leading = { AccountAvatar(creator, state.details?.avatarUrl, COMPACT_AVATAR_SIZE) },
        onBack = onBack,
    ) {
        AccountFeedSegments(state.tabs, state.selectedFeed) { onAction(CreatorAction.SelectFeed(it)) }
        if (creator.canFollow) CreatorFollowButton(followed = followed, onClick = onToggleFollow, enabled = followEnabled)
        if (bio != null) {
            IconToggleButton(checked = bioShown, onCheckedChange = { bioShown = it }) {
                Icon(Icons.Outlined.Info, contentDescription = "Bio")
            }
        }
        CreatorLinkActions(creator, onOpenUrl)
    }
    if (bioShown && bio != null) AccountBio(bio)
}

/** Open in browser and Share, when the creator has a profile link. */
@Composable
private fun CreatorLinkActions(creator: CreatorProfile, onOpenUrl: (String) -> Unit) {
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
    pageDemand: FeedPageDemandControl,
    onAction: (CreatorAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    state.errorMessage?.takeIf { state.canLoadMore }?.let { message ->
        DismissibleFailure(message) { dismiss ->
            FeedErrorTile(message = message, onRetry = { onAction(CreatorAction.RetryPage) }, onDismiss = dismiss)
        }
    }
    FeedContinueLoading(pageDemand)
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

        // Pull to refresh is how paging resumes after a dismissed page failure.
        else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = { onAction(CreatorAction.Refresh) }) {
            CreatorGrid(state, feed, cards, gridState, onAction, onLongPress)
        }
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
        footerMessage = pagedFeedFooterMessage(
            resolvingDurations = feed.durationFilter.readiness.isResolving,
            hasVisibleResults = feed.visibleResults.isNotEmpty(),
            canLoadMore = state.canLoadMore,
            requestRunning = state.isRefreshing || state.isPaging,
            failed = state.errorMessage != null,
        ),
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
    state.results.isEmpty() -> "No ${state.selectedFeed.noun} found for this creator."
    !feed.visibilityFilters.animatedDurationRange.isFullRange ->
        "No animated media found in the selected duration range."
    else -> "No ${state.selectedFeed.noun} match the current filters."
}

/** Keeps the uploads feed's keys as they were before accounts had other feeds. */
private val AccountFeed.retainKey: String
    get() = if (this == AccountFeed.UPLOADS) "creator" else "creator-${name.lowercase()}"

private val AccountFeed.noun: String get() = name.lowercase()

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
