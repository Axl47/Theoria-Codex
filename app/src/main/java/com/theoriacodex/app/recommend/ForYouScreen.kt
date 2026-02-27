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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
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
    onToggleLike: (Post) -> Unit,
    onOpenViewer: (List<Post>, ViewerLaunchContext) -> Unit,
    onGoToSearch: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    var showSortSheet by remember { mutableStateOf(false) }
    val visibleResults = coordinator.results

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
                        text = "No recommendations yet. Tap Refresh to try a broader seed.",
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
                                onOpenViewer(visibleResults, context)
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
            onSelectSort = { sort ->
                scope.launch {
                    coordinator.setSortMode(sort)
                }
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

private const val FOR_YOU_PREFETCH_RATIO = 0.8f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForYouSortSheet(
    selectedSort: SortMode,
    onSelectSort: (SortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
