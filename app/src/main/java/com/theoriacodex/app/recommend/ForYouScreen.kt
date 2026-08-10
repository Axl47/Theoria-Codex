package com.theoriacodex.app.recommend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.durationFilterReadiness
import com.theoriacodex.app.media.durationStatesByPostId
import com.theoriacodex.app.media.knownMediaDurations
import com.theoriacodex.app.media.mediaDurationKeysByPostId
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    state: ForYouUiState,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
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
) {
    val gridState = rememberLazyStaggeredGridState()
    var showSortSheet by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(
            minBucket = durationMinBucket,
            maxBucket = durationMaxBucket,
        )
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange
    val visibilityFilters = remember(animatedOnly, animatedDurationRange) {
        SearchVisibilityFilters(
            animatedOnly = animatedOnly,
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
    val durationKeysByPostId = remember(state.results) {
        mediaDurationKeysByPostId(state.results)
    }
    val acquiredDurations = remember(state.results, durationStates, durationKeysByPostId) {
        knownMediaDurations(state.results, durationStates, durationKeysByPostId)
    }
    val durationDecisionStates = remember(state.results, durationStates, durationKeysByPostId) {
        durationStatesByPostId(state.results, durationStates, durationKeysByPostId)
    }
    val durationReadiness = remember(
        state.results,
        animatedDurationFilterActive,
        durationDecisionStates,
    ) {
        durationFilterReadiness(
            state.results,
            animatedDurationFilterActive,
            durationDecisionStates,
        )
    }
    val visibleResults = remember(
        state.results,
        visibilityFilters,
        unknownAnimatedDurationPolicy,
        acquiredDurations,
    ) {
        filterSearchResults(
            results = state.results,
            filters = visibilityFilters,
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
            knownDurationMsByPostId = acquiredDurations,
        )
    }
    LaunchedEffect(animatedDurationFilterActive) {
        onDurationFilterChanged(animatedDurationFilterActive)
    }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)

    LaunchedEffect(
        animatedOnly,
        visibleResults.size,
        state.isRefreshing,
        state.isPaging,
        state.canLoadMore,
        animatedDurationFilterActive,
        durationReadiness.pendingCount,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.isPaging
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (state.isRefreshing || state.isPaging || !state.canLoadMore) return@collect
            if (durationReadiness.isResolving) return@collect
            if (visibleResults.isEmpty() || lastVisibleIndex < 0) {
                if (animatedDurationFilterActive && state.results.isNotEmpty()) {
                    onAction(ForYouAction.LoadNextPage)
                }
                return@collect
            }

            val triggerIndex = ((visibleResults.lastIndex.coerceAtLeast(0)) * FOR_YOU_PREFETCH_RATIO)
                .toInt()
                .coerceAtLeast(0)
            if (lastVisibleIndex >= triggerIndex) {
                onAction(ForYouAction.LoadNextPage)
            }
        }
    }

    LaunchedEffect(
        animatedOnly,
        visibleResults.size,
        state.results.size,
        state.isRefreshing,
        state.isPaging,
        state.canLoadMore,
        animatedDurationFilterActive,
        durationReadiness.pendingCount,
    ) {
        if (!animatedOnly && !animatedDurationFilterActive) return@LaunchedEffect
        if (visibleResults.isNotEmpty()) return@LaunchedEffect
        if (state.results.isEmpty()) return@LaunchedEffect
        if (state.isRefreshing || state.isPaging || !state.canLoadMore) return@LaunchedEffect
        if (durationReadiness.isResolving) return@LaunchedEffect
        onAction(ForYouAction.LoadNextPage)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                active = animatedOnly || animatedDurationFilterActive ||
                    state.sortMode != SortMode.NEWEST,
                contentDescription = "Filter and sort recommendations",
                onClick = {
                    showSortSheet = true
                },
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("For You", style = MaterialTheme.typography.titleLarge)
                ForYouSourceSelector(
                    selectedSource = state.selectedSource,
                    availableSources = state.availableSources,
                    enabled = !state.isRefreshing && !state.isPaging,
                    expanded = showSourceMenu,
                    onExpandedChange = { showSourceMenu = it },
                    onSourceSelected = { source ->
                        showSourceMenu = false
                        onAction(ForYouAction.SelectSource(source))
                    },
                )
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
                        contentDescription = "Shuffle recommendations",
                    )
                }
            }
        }

        if (state.seedSummaryBySource.isNotEmpty()) {
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

        when {
            state.activeProfileLikesCount == 0 -> {
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

            state.isRefreshing && visibleResults.isEmpty() -> {
                FeedLoadingState()
            }

            state.errorMessage != null && visibleResults.isEmpty() -> {
                FeedErrorTile(
                    message = state.errorMessage.orEmpty(),
                    onRetry = { onAction(ForYouAction.Refresh(shuffle = true)) },
                )
            }

            visibleResults.isEmpty() -> {
                FeedEmptyTile(
                    message = if (durationReadiness.isResolving) {
                            "Resolving durations…"
                        } else if (animatedOnly && state.results.isNotEmpty()) {
                            if (!visibilityFilters.animatedDurationRange.isFullRange) {
                                "No animated media found in the selected duration range."
                            } else {
                                "No animated media found for this feed yet."
                            }
                        } else {
                            "No recommendations yet. Tap Refresh to try a broader seed."
                        },
                )
            }

            else -> {
                TwoColumnPostStaggeredGrid(
                    posts = visibleResults,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    showPagingTile = state.isPaging,
                    footerMessage = if (durationReadiness.isResolving) {
                        "Resolving durations…"
                    } else {
                        null
                    },
                ) { index, post ->
                    SearchResultCard(
                        post = post,
                        pixivUgoiraClient = pixivUgoiraClient,
                        acquiredDurationMs = acquiredDurations[post.id],
                        showSourceBadge = true,
                        liked = post.id in likedPostIds,
                        onToggleLike = { onToggleLike(post) },
                        onClick = {
                            onAction(
                                ForYouAction.OpenResult(
                                    index = index,
                                    scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                    visibleResults = visibleResults,
                                    visibilityFilters = visibilityFilters,
                                )
                            )
                        },
                        onLongPress = { selectedActionPost = post },
                        onViewportChanged = { visible ->
                            onDurationPostVisibilityChanged(post, visible)
                        },
                        onAuthoritativeDurationKnown = { durationMs ->
                            onAuthoritativeDurationKnown(post, durationMs)
                        },
                    )
                }
            }
        }
        }
    }

    selectedActionPost?.let { post ->
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onGoToSearch = onGoToSearch,
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                PostTagActionSection(
                    post = post,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )
            },
        )
    }

    if (showSortSheet) {
        ForYouSortSheet(
            selectedSort = state.sortMode,
            animatedOnly = animatedOnly,
            animatedDurationRange = animatedDurationRange,
            onSelectSort = { sort ->
                onAction(ForYouAction.SelectSort(sort))
            },
            onAnimatedOnlyChange = { enabled ->
                animatedOnly = enabled
            },
            onAnimatedDurationRangeChange = { range ->
                durationMinBucket = range.normalizedMinBucket
                durationMaxBucket = range.normalizedMaxBucket
            },
            onDismiss = { showSortSheet = false },
        )
    }
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
    val selectedLabel = selectedSource?.displayName() ?: "Unified"
    Box {
        Row(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = "Select For You source"
                }
                .expandableControlSemantics(
                    expanded = expanded,
                    description = "${if (expanded) "Expanded" else "Collapsed"}; " +
                        "$selectedLabel selected",
                    onExpandedChange = onExpandedChange,
                )
                .clickable(
                    enabled = enabled,
                    role = Role.DropdownList,
                    onClick = { onExpandedChange(true) },
                )
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .testTag("For You source icon"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = { Text("Unified") },
                onClick = { onSourceSelected(null) },
            )
            availableSources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName()) },
                    onClick = { onSourceSelected(source) },
                )
            }
        }
    }
}

private const val FOR_YOU_PREFETCH_RATIO = 0.8f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForYouSortSheet(
    selectedSort: SortMode,
    animatedOnly: Boolean,
    animatedDurationRange: AnimatedDurationRange,
    onSelectSort: (SortMode) -> Unit,
    onAnimatedOnlyChange: (Boolean) -> Unit,
    onAnimatedDurationRangeChange: (AnimatedDurationRange) -> Unit,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss) {
            Text("Media Types", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = animatedOnly,
                        onClick = { onAnimatedOnlyChange(!animatedOnly) },
                        label = { Text("Animated only") },
                    )
                }
            }
            AnimatedDurationRangeControl(
                range = animatedDurationRange,
                onRangeChange = onAnimatedDurationRangeChange,
            )

            Text("Sort", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SortMode.entries.toList()) { mode ->
                    FilterChip(
                        selected = selectedSort == mode,
                        onClick = {
                            onSelectSort(mode)
                            onDismiss()
                        },
                        label = { Text(sortModeLabel(mode)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
    }
}

private fun sortModeLabel(sortMode: SortMode): String {
    return when (sortMode) {
        SortMode.NEWEST -> "Newest"
        SortMode.POPULAR -> "Popular"
        SortMode.TOP -> "Top"
        SortMode.RANDOM -> "Random"
    }
}
