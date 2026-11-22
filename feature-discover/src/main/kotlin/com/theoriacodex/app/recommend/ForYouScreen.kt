package com.theoriacodex.app.recommend

import com.theoriacodex.app.ui.components.FeedGrid
import com.theoriacodex.app.ui.components.rememberFeedView
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.search.sortModeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouEmptyReason
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedFailureTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.triage.triageSeed
import com.theoriacodex.app.ui.navigation.LocalTriageOpener
import com.theoriacodex.app.ui.navigation.TriageOpener
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.navigation.rememberTriageAction
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedScrollSignal
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.SourceSelector
import com.theoriacodex.app.ui.components.pagedFeedFooterMessage
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: ForYouUiState,
    related: RelatedShelfBinding,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    durations: FeedDurations = FeedDurations.NONE,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    onToggleLike: (Post) -> Unit,
    onAction: (ForYouAction) -> Unit,
    creatorBrowsingSources: Set<SourceKey> = emptySet(),
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onGoToSearch: () -> Unit,
    onOpenSourceAccounts: () -> Unit = {},
    onSelectProfile: (String) -> Unit = {},
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
) {
    BackHandler(enabled = !state.home.visible) { onAction(ForYouAction.ShowHome) }
    val savedStates = rememberSaveableStateHolder()
    val gridState = rememberRetainedFeedGridState("for-you")
    ForYouGridPositionEffect(state, gridState)
    var showSortSheet by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var why by remember { mutableStateOf<ForYouWhyTarget?>(null) }
    val feed = rememberForYouFeed(state, related.state, fabRestoreState, durations)
    LaunchedEffect(feed.durationFilterActive) { durations.onFilterChanged(feed.durationFilterActive) }
    DurationRouteEnvironmentEffect(gridState, durations.onEnvironmentChanged)
    val filterScroll = rememberFeedScrollSignal()
    val filterLabels = forYouFilterLabels(state.sortMode, feed)
    val clearFilters: () -> () -> Unit = { clearForYouFilters(state.sortMode, fabRestoreState, onFabRestoreStateChange, onAction) }
    val pageDemand = rememberForYouPageDemand(state, feed, gridState, onAction)
    val triage = rememberForYouTriage(state, feed, onAction)
    val cards = rememberFeedCardEnvironment(
        pixivUgoiraClient, likedPostIds, feed.durations.knownDurationMsByPostId, durations, onToggleLike, resolvePostById,
    )

    ForYouWhyHost(why, state, onAction, onClose = { why = null }) {
        AnimatedContent(
            targetState = state.home.visible,
            transitionSpec = { depthTransform(forward = !targetState) },
            label = "For You view",
        ) { homeVisible ->
            if (homeVisible) {
                savedStates.SaveableStateProvider("overview") {
                    ForYouHomeScreen(state, cards, feed::filterHomePosts,
                        { selectedActionPost = it }, onAction, { showSortSheet = true }, filterLabels, clearFilters,
                        onOpenPreview = { id, posts, index -> onAction(ForYouAction.OpenSectionResult(id, index, posts, feed.filters)) },
                        onOpenWhy = { why = it }, onSelectProfile = onSelectProfile, onTriageShelf = triage.shelf)
                }
            } else {
                ForYouResults(
                    state, related, feed, cards, gridState, pageDemand, filterScroll, filterLabels, clearFilters, showSourceMenu,
                    onSourceMenuChange = { showSourceMenu = it }, onShowFilters = { showSortSheet = true },
                    onOpenSourceAccounts = onOpenSourceAccounts, onOpenWhy = { why = it },
                    onAction = onAction, onLongPress = { selectedActionPost = it },
                )
            }
        }
    }

    selectedActionPost?.let { post ->
        FeedPostActionSheet(
            post, creatorBrowsingSources, { selectedActionPost = null }, onSaveToDevice, onRequestSaveToCodex,
            onGoToSearch, liked = post.id in likedPostIds, onToggleLike = onToggleLike,
            onShowRelated = { related.controller.show(post) },
        )
    }

    if (showSortSheet) {
        ForYouSortSheet(
            state.sortMode, fabRestoreState, feed, onFabRestoreStateChange, onAction,
            // The sheet reset has no undo affordance, so the undo callback is intentionally discarded.
            onReset = @Suppress("IgnoredReturnValue") { clearFilters() },
            onTriage = triage.sheet,
            onDismiss = { showSortSheet = false },
        )
    }
}

/**
 * A new seed starts from the top, as does reselecting the tab over the grid; the position saved with the
 * current seed survives recreation.
 */
@Composable
private fun ForYouGridPositionEffect(state: ForYouUiState, gridState: LazyStaggeredGridState) {
    ScrollToTopOnTabReselect(TopLevelDestination.ForYou) {
        if (!state.home.visible) gridState.animateScrollToItem(0)
    }
    val seedId = state.seedId
    var positionedSeed by rememberSaveable { mutableStateOf(seedId) }
    LaunchedEffect(seedId) {
        if (positionedSeed != seedId) {
            gridState.scrollToItem(0)
            positionedSeed = seedId
        }
    }
}

private fun forYouFilterLabels(sortMode: SortMode, feed: FeedView): List<String> = listOfNotNull(
    sortModeLabel(sortMode).takeIf { sortMode != SortMode.NEWEST },
    "Animated".takeIf { feed.filters.animatedOnly },
    animatedDurationRangeLabel(feed.filters.animatedDurationRange).takeIf { feed.durationFilterActive },
)

@Composable
private fun ForYouResults(
    state: ForYouUiState,
    related: RelatedShelfBinding,
    feed: FeedView,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    filterScroll: FeedScrollSignal,
    filterLabels: List<String>,
    clearFilters: () -> () -> Unit,
    showSourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit,
    onShowFilters: () -> Unit,
    onOpenSourceAccounts: () -> Unit,
    onOpenWhy: (ForYouWhyTarget) -> Unit,
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
            ForYouSeedSummary(state, onOpenWhy)
            com.theoriacodex.app.search.StatusRow(
                statuses = state.actionableStatuses,
                retryEnabled = !state.isRefreshing && !state.isPaging,
                onRetrySource = { onAction(ForYouAction.RetrySource(it)) },
                onOpenAccounts = onOpenSourceAccounts,
            )
            FeedContinueLoading(pageDemand)
            ForYouFeedBody(state, related, feed, cards, gridState, onAction, onLongPress)
        }
    }
}

/** Triage from For You: over a home shelf ([shelf]; see [rememberShelfTriage]) and from the filter sheet ([sheet]). */
private class ForYouTriage(val shelf: ((String?) -> Unit)?, val sheet: (() -> Unit)?)

/** On home the sheet triages Your mix, home's own feed; the grid's results there are whichever shelf was open last. */
@Composable
private fun rememberForYouTriage(state: ForYouUiState, feed: FeedView, onAction: (ForYouAction) -> Unit): ForYouTriage {
    val grid = rememberTriageAction(ViewerStreamSource.FOR_YOU, state.queryHash, feed.triageSeed())
    val shelf = rememberShelfTriage(state, feed, onAction)
    val mix = shelf?.takeIf { state.home.mixedFeed?.page?.items.orEmpty().isNotEmpty() }
    return ForYouTriage(shelf, if (state.home.visible) mix?.let { triage -> { triage(null) } } else grid)
}

/**
 * Triage over a home shelf: section `id`, or Your mix for null. It opens the shelf as the grid's feed, which the deck
 * pages through and returns to, then the deck, seeded with the shelf's posts the grid's filters keep. Null when
 * nothing in the app provides a deck.
 */
@Composable
private fun rememberShelfTriage(state: ForYouUiState, feed: FeedView, onAction: (ForYouAction) -> Unit): ((String?) -> Unit)? {
    val opener = LocalTriageOpener.current
    val latestState by rememberUpdatedState(state)
    val latestFeed by rememberUpdatedState(feed)
    return remember(opener, onAction) {
        if (opener === TriageOpener.None) return@remember null
        triage@{ id: String? ->
            val shelf = latestState.home.shelf(id) ?: return@triage
            val posts = latestFeed.filterHomePosts(shelf.page.items).ifEmpty { return@triage }
            onAction(if (id == null) ForYouAction.OpenMixedFeed else ForYouAction.OpenSection(id))
            opener.openTriage(TriageRequest(ViewerStreamSource.FOR_YOU, shelf.queryHash), latestFeed.triageSeed().copy(posts = posts))
        }
    }
}

/** Home section [id]'s feed, or Your mix's for null. */
private fun ForYouHomeState.shelf(id: String?): ForYouFeedResult? =
    if (id == null) mixedFeed else sections.firstOrNull { it.id == id }?.feed

/** Home shelves take the grid's media filters and known lengths; For You has no mark filters to apply. */
private fun FeedView.filterHomePosts(posts: List<Post>): List<Post> = filterSearchResults(
    posts, filters, likedPostIds = emptySet(), savedPostIds = emptySet(),
    knownDurationMsByPostId = durations.knownDurationMsByPostId,
)

/** The For You grid: its results after the sheet's filters, known lengths, and the related shelf. */
@Composable
private fun rememberForYouFeed(
    state: ForYouUiState,
    relatedState: RelatedPostsUiState,
    fabRestoreState: FeedFabRestoreState,
    durations: FeedDurations,
): FeedView {
    val animatedOnly = fabRestoreState.animatedOnly
    val durationMinBucket = fabRestoreState.durationMinBucket
    val durationMaxBucket = fabRestoreState.durationMaxBucket
    val visibilityFilters = remember(animatedOnly, durationMinBucket, durationMaxBucket) {
        SearchVisibilityFilters(
            animatedOnly = animatedOnly,
            animatedDurationRange = AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket),
        )
    }
    // Duration metadata is only current for the exact list it projected, so keep this list's identity stable.
    // Home sections are shelves, so the results keep their order while the home's lengths resolve.
    val home = state.home
    val durationPosts = remember(home.visible, home.sections, home.mixedFeed, state.results) {
        if (home.visible) {
            home.sections.flatMap { it.feed.page.items } + home.mixedFeed?.page?.items.orEmpty()
        } else state.results
    }
    // For You ignores liked, saved and watched marks: it hides nothing by them, so it passes no FeedMarks.
    return rememberFeedView(
        "for-you", state.results, visibilityFilters, durations, relatedState, durationPosts = durationPosts,
    )
}

@Composable
private fun rememberForYouPageDemand(
    state: ForYouUiState,
    feed: FeedView,
    gridState: LazyStaggeredGridState,
    onAction: (ForYouAction) -> Unit,
) = rememberFeedPageDemand(
    input = FeedPageDemandInput(
        contextKey = "${state.activeProfileId}:${state.selectedSource}:${state.sortMode}:${state.seedId}",
        completedGeneration = state.nextRequestGeneration,
        canonicalCount = state.results.size,
        visibleCount = feed.visiblePosts.size,
        canLoadMore = state.canLoadMore,
        ready = !state.home.visible && state.seedSummaryBySource.isNotEmpty(),
        refreshing = state.isRefreshing,
        paging = state.isPaging,
        failed = state.errorMessage != null,
        durationsBlockPaging = feed.durations.blocksPaging,
    ),
    filters = feed.filters,
    gridState = gridState,
    projection = feed.related.projection,
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
                    contentDescription = "Hide these tags",
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

/** The drilled-in feed's tags per source; a tap opens the same why panel as a shelf's title. */
@Composable
private fun ForYouSeedSummary(state: ForYouUiState, onOpenWhy: (ForYouWhyTarget) -> Unit) {
    if (state.seedSummaryBySource.isEmpty()) return
    val section = state.home.activeSection
    val target = ForYouWhyTarget(section?.title ?: "Your mix", state.seedSummaryBySource, section?.id, section?.pinned == true)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.seedSummaryBySource.entries.toList()) { (source, tags) ->
            Card(Modifier.clickable(onClickLabel = "Show tags") { onOpenWhy(target) }) {
                Text(
                    text = "${source.displayName()}: ${tags.joinToString(" + ")}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun forYouFooterMessage(state: ForYouUiState, feed: FeedView): String? = pagedFeedFooterMessage(
    resolvingDurations = feed.durations.readiness.isResolving,
    hasVisibleResults = feed.visiblePosts.isNotEmpty(),
    canLoadMore = state.canLoadMore,
    requestRunning = state.isRefreshing || state.isPaging,
    // A source whose next page failed leaves paging until it is retried; that is not the end.
    failed = state.errorMessage != null || state.retryableSources.isNotEmpty(),
)

@Composable
private fun ForYouFeedBody(
    state: ForYouUiState,
    related: RelatedShelfBinding,
    feed: FeedView,
    cards: FeedCardEnvironment,
    gridState: LazyStaggeredGridState,
    onAction: (ForYouAction) -> Unit,
    onLongPress: (Post) -> Unit,
) {
    when {
        state.emptyReason == ForYouEmptyReason.NO_LIKES -> ForYouNoLikes(onAction)

        state.isRefreshing && feed.visiblePosts.isEmpty() -> FeedLoadingState()

        state.errorMessage != null && feed.visiblePosts.isEmpty() -> {
            FeedFailureTile(state.errorMessage.orEmpty(), onRetry = { onAction(ForYouAction.Refresh(shuffle = true)) })
        }

        feed.related.projection.entries.isEmpty() -> FeedEmptyTile(message = forYouEmptyMessage(state, feed))

        else -> {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onAction(ForYouAction.Refresh(shuffle = false)) },
                modifier = Modifier.fillMaxSize(),
            ) {
                FeedGrid(
                    view = feed,
                    state = gridState,
                    footerMessage = forYouFooterMessage(state, feed),
                    showPagingTile = state.isPaging,
                    post = { index, post ->
                        FeedPostCard(
                            post = post,
                            environment = cards,
                            showWatchHistory = true,
                            showSourceBadge = true,
                            onClick = {
                                onAction(
                                    ForYouAction.OpenResult(
                                        index = index,
                                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        visibleResults = feed.visiblePosts,
                                        visibilityFilters = feed.filters,
                                    )
                                )
                            },
                            onLongPress = { onLongPress(post) },
                        )
                    },
                    shelf = {
                        FeedRelatedPostsShelf(related, feed.related, cards, onLongPress)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ForYouNoLikes(onAction: (ForYouAction) -> Unit) {
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

private fun forYouEmptyMessage(state: ForYouUiState, feed: FeedView): String = when {
    feed.durations.readiness.isResolving -> "Resolving durations…"
    !(feed.filters.animatedOnly || feed.durationFilterActive) || state.results.isEmpty() ->
        "No recommendations yet"
    !feed.filters.animatedDurationRange.isFullRange ->
        "No animated media found in the selected duration range."
    else -> "No animated media found for this feed yet."
}

@Composable
fun ForYouSourceSelector(
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
    feed: FeedView,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onAction: (ForYouAction) -> Unit,
    onReset: () -> Unit,
    onTriage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset, onTriage = onTriage) {
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), selectedSort == mode) {
                    onFabRestoreStateChange(fabRestoreState.copy(sortMode = mode.name))
                    onAction(ForYouAction.SelectSort(mode))
                }
            }
        }
        FilterSection("Show") {
            FilterChoiceChip("Animated only", feed.filters.animatedOnly) {
                onFabRestoreStateChange(fabRestoreState.copy(animatedOnly = !feed.filters.animatedOnly))
            }
        }
        AnimatedDurationRangeControl(
            range = feed.filters.animatedDurationRange,
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
