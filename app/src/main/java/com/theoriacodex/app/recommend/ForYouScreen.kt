package com.theoriacodex.app.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.animatedDurationResolutionCandidates
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouScreen(
    coordinator: ForYouCoordinator,
    activeProfileId: String,
    activeProfileName: String,
    likesCount: Int,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    onToggleLike: (Post) -> Unit,
    onBlacklistCurrentSeed: () -> Unit,
    onOpenViewer: (List<Post>, ViewerLaunchContext, SearchVisibilityFilters) -> Unit,
    onGoToSearch: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    var showSortSheet by remember { mutableStateOf(false) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(
            minBucket = durationMinBucket,
            maxBucket = durationMaxBucket,
        )
    }
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
    val visibleResults = remember(coordinator.results, visibilityFilters, unknownAnimatedDurationPolicy) {
        filterSearchResults(
            results = coordinator.results,
            filters = visibilityFilters,
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        )
    }
    val durationResolutionRequests = remember(coordinator.seedId) { mutableSetOf<PostId>() }
    LaunchedEffect(coordinator.results, visibilityFilters, unknownAnimatedDurationPolicy, coordinator.seedId) {
        if (unknownAnimatedDurationPolicy != UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND) return@LaunchedEffect
        val candidates = animatedDurationResolutionCandidates(
            results = coordinator.results,
            filters = visibilityFilters,
        ).filter { post -> durationResolutionRequests.add(post.id) }
            .take(FOR_YOU_DURATION_RESOLVE_BATCH_SIZE)
        candidates.forEach { post ->
            runCatching { coordinator.resolvePostForFeed(post.id) }
        }
    }

    LaunchedEffect(activeProfileId, likesCount) {
        if (likesCount == 0) {
            coordinator.clear()
        } else {
            val shouldRefresh = coordinator.results.isEmpty() ||
                coordinator.activeProfileId != activeProfileId ||
                coordinator.activeProfileLikesCount != likesCount
            if (shouldRefresh) {
                coordinator.refresh(shuffle = false)
            }
        }
    }

    LaunchedEffect(
        animatedOnly,
        visibleResults.size,
        coordinator.loading,
        coordinator.loadingMore,
        coordinator.canLoadMore,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to coordinator.loadingMore
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (coordinator.loading || coordinator.loadingMore || !coordinator.canLoadMore) return@collect
            if (visibleResults.isEmpty() || lastVisibleIndex < 0) return@collect

            val triggerIndex = ((visibleResults.lastIndex.coerceAtLeast(0)) * FOR_YOU_PREFETCH_RATIO)
                .toInt()
                .coerceAtLeast(0)
            if (lastVisibleIndex >= triggerIndex) {
                coordinator.loadNextPage()
            }
        }
    }

    LaunchedEffect(
        animatedOnly,
        visibleResults.size,
        coordinator.results.size,
        coordinator.loading,
        coordinator.loadingMore,
        coordinator.canLoadMore,
    ) {
        if (!animatedOnly) return@LaunchedEffect
        if (visibleResults.isNotEmpty()) return@LaunchedEffect
        if (coordinator.results.isEmpty()) return@LaunchedEffect
        if (coordinator.loading || coordinator.loadingMore || !coordinator.canLoadMore) return@LaunchedEffect
        coordinator.loadNextPage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = {
                    showSortSheet = true
                },
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Sort recommendations",
                )
            }
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
                Text(
                    text = activeProfileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    enabled = likesCount > 0 && !coordinator.loading && coordinator.seedSummaryBySource.isNotEmpty(),
                    onClick = onBlacklistCurrentSeed,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Blacklist current recommendation tags",
                    )
                }
                IconButton(
                    enabled = likesCount > 0 && !coordinator.loading,
                    onClick = {
                        scope.launch {
                            coordinator.refresh(shuffle = true)
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Shuffle recommendations",
                    )
                }
            }
        }

        if (coordinator.seedSummaryBySource.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(coordinator.seedSummaryBySource.entries.toList()) { (source, tags) ->
                    Card {
                        Text(
                            text = "${source.name}: ${tags.joinToString(" + ")}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        when {
            likesCount == 0 -> {
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
                        TextButton(onClick = onGoToSearch) {
                            Text("Go to Search")
                        }
                    }
                }
            }

            coordinator.loading && visibleResults.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            coordinator.errorMessage != null && visibleResults.isEmpty() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(coordinator.errorMessage.orEmpty())
                        TextButton(onClick = {
                            scope.launch { coordinator.refresh(shuffle = true) }
                        }) {
                            Text("Retry")
                        }
                    }
                }
            }

            visibleResults.isEmpty() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (animatedOnly && coordinator.results.isNotEmpty()) {
                            if (!visibilityFilters.animatedDurationRange.isFullRange) {
                                "No animated media found in the selected duration range."
                            } else {
                                "No animated media found for this feed yet."
                            }
                        } else {
                            "No recommendations yet. Tap Refresh to try a broader seed."
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    verticalItemSpacing = 6.dp,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = visibleResults,
                        key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
                    ) { index, post ->
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = pixivUgoiraClient,
                            showSourceBadge = true,
                            displayTag = coordinator.displayTagFor(post),
                            liked = post.id in likedPostIds,
                            onToggleLike = { onToggleLike(post) },
                            onClick = {
                                val context = coordinator.buildViewerLaunchContext(
                                    startIndex = index,
                                    scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                )
                                onOpenViewer(visibleResults, context, visibilityFilters)
                            },
                        )
                    }

                    if (coordinator.loadingMore) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showSortSheet) {
        ForYouSortSheet(
            selectedSort = coordinator.sortMode,
            animatedOnly = animatedOnly,
            animatedDurationRange = animatedDurationRange,
            onSelectSort = { sort ->
                scope.launch {
                    coordinator.setSortMode(sort)
                }
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

private const val FOR_YOU_PREFETCH_RATIO = 0.8f
private const val FOR_YOU_DURATION_RESOLVE_BATCH_SIZE = 8

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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
}

private fun sortModeLabel(sortMode: SortMode): String {
    return when (sortMode) {
        SortMode.NEWEST -> "Newest"
        SortMode.POPULAR -> "Popular"
        SortMode.TOP -> "Top"
        SortMode.RANDOM -> "Random"
    }
}
