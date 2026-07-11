package com.theoriacodex.app.recommend

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.probeRemoteVideoDurationMs
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.animatedDurationResolutionCandidates
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
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
    onToggleLike: (Post) -> Unit,
    onAction: (ForYouAction) -> Unit,
    resolvePost: suspend (PostId) -> Post? = { null },
    rememberResolvedPost: (Post) -> Unit = {},
    displayTagFor: (Post) -> String? = { null },
) {
    val gridState = rememberLazyStaggeredGridState()
    var showSortSheet by remember { mutableStateOf(false) }
    var showSourceMenu by remember { mutableStateOf(false) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MAX_BUCKET) }
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
    val visibleResults = remember(state.results, visibilityFilters, unknownAnimatedDurationPolicy) {
        filterSearchResults(
            results = state.results,
            filters = visibilityFilters,
            likedPostIds = emptySet(),
            savedPostIds = emptySet(),
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        )
    }
    val durationResolutionRequests = remember(state.seedId) { mutableSetOf<PostId>() }
    LaunchedEffect(state.results, visibilityFilters, unknownAnimatedDurationPolicy, state.seedId) {
        if (unknownAnimatedDurationPolicy != UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND) return@LaunchedEffect
        val candidates = animatedDurationResolutionCandidates(
            results = state.results,
            filters = visibilityFilters,
        ).filter { post -> durationResolutionRequests.add(post.id) }
            .take(FOR_YOU_DURATION_RESOLVE_BATCH_SIZE)
        candidates.forEach { post ->
            val resolved = runCatchingPreservingCancellation {
                resolvePost(post.id)
            }.getOrNull()
            val candidate = resolved ?: post
            if (animatedDurationMs(candidate) == null) {
                val probedDurationMs = probeRemoteVideoDurationMs(candidate)
                if (probedDurationMs != null) {
                    rememberResolvedPost(candidate.copy(durationMs = probedDurationMs))
                }
            }
        }
    }

    LaunchedEffect(
        animatedOnly,
        visibleResults.size,
        state.isRefreshing,
        state.isPaging,
        state.canLoadMore,
        animatedDurationFilterActive,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.isPaging
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (state.isRefreshing || state.isPaging || !state.canLoadMore) return@collect
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
    ) {
        if (!animatedOnly && !animatedDurationFilterActive) return@LaunchedEffect
        if (visibleResults.isNotEmpty()) return@LaunchedEffect
        if (state.results.isEmpty()) return@LaunchedEffect
        if (state.isRefreshing || state.isPaging || !state.canLoadMore) return@LaunchedEffect
        onAction(ForYouAction.LoadNextPage)
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
                Box {
                    Row(
                        modifier = Modifier
                            .clickable(
                                enabled = !state.isRefreshing && !state.isPaging,
                                onClick = { showSourceMenu = true },
                            )
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.selectedSource?.displayName() ?: "Unified",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select For You source",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showSourceMenu,
                        onDismissRequest = { showSourceMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unified") },
                            onClick = {
                                showSourceMenu = false
                                onAction(ForYouAction.SelectSource(null))
                            },
                        )
                        state.availableSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.displayName()) },
                                onClick = {
                                    showSourceMenu = false
                                    onAction(ForYouAction.SelectSource(source))
                                },
                            )
                        }
                    }
                }
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
                            text = "${source.name}: ${tags.joinToString(" + ")}",
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
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && visibleResults.isEmpty() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.errorMessage.orEmpty())
                        TextButton(onClick = {
                            onAction(ForYouAction.Refresh(shuffle = true))
                        }) {
                            Text("Retry")
                        }
                    }
                }
            }

            visibleResults.isEmpty() -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (animatedOnly && state.results.isNotEmpty()) {
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
                            displayTag = displayTagFor(post),
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
                        )
                    }

                    if (state.isPaging) {
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
