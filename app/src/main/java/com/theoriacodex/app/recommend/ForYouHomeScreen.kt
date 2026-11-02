package com.theoriacodex.app.recommend

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.app.ui.components.ReorderListState
import com.theoriacodex.app.ui.components.ListReorderHandle
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.POST_SHELF_MEDIA_ASPECT_RATIO
import com.theoriacodex.app.ui.components.PostShelfSection
import com.theoriacodex.app.viewer.ViewerReturnColumn
import com.theoriacodex.app.viewer.ViewerReturnShelf
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

@Composable
internal fun ForYouHomeScreen(
    state: ForYouUiState,
    likedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient?,
    filterPosts: (List<Post>) -> List<Post>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onToggleLike: (Post) -> Unit,
    onLongPress: (Post) -> Unit,
    onAction: (ForYouAction) -> Unit,
    onShowFilters: () -> Unit,
    filtersActive: Boolean,
    onOpenPreview: (String?, List<Post>, Int) -> Unit,
) {
    val scroll = rememberLazyListState()
    var sourceMenu by remember { mutableStateOf(false) }
    val ordering = remember { ReorderListState(state.home.sections, ForYouSection::id) }
    LaunchedEffect(state.home.sections) { ordering.synchronize(state.home.sections) }
    val onReorder = {
        ordering.toggle(state.home.sections) { onAction(ForYouAction.ReorderSections(it)) }
    }
    Scaffold(contentWindowInsets = WindowInsets(0), floatingActionButton = {
        FeedFilterFab(active = filtersActive, onClick = onShowFilters, contentDescription = "Filter and sort recommendations")
    }) { padding ->
        if (ordering.reorderMode) {
            ForYouReorderList(state, ordering, sourceMenu, { sourceMenu = it }, onAction, onReorder,
                Modifier.padding(padding))
        } else {
            ForYouOverview(state, scroll, Modifier.padding(padding), filterPosts, onAction,
                header = { ForYouHomeHeader(state, sourceMenu, { sourceMenu = it }, onAction, false, onReorder) },
                preview = { id, posts ->
                    ForYouPreviewPosts(id ?: "mixed", posts, likedPostIds, pixivUgoiraClient, durationStateForPost,
                        onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike, onLongPress) { index ->
                        onOpenPreview(id, posts, index)
                    }
                })
        }
    }
}

@Composable
private fun ForYouOverview(
    state: ForYouUiState, scroll: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier, filterPosts: (List<Post>) -> List<Post>, onAction: (ForYouAction) -> Unit,
    header: @Composable () -> Unit, preview: @Composable (String?, List<Post>) -> Unit,
) {
        val shelfIds = listOf(null, ViewerReturnShelfId.ForYou("mixed")) +
            state.home.sections.map { ViewerReturnShelfId.ForYou(it.id) } + listOf(null)
        ViewerReturnColumn(shelfIds, scroll) {
        LazyColumn(state = scroll, modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "header") {
                header()
            }
            item(key = "mixed") {
                val posts = filterPosts(state.home.mixedFeed?.page?.items.orEmpty()).take(10)
                PostShelfSection("For You", null, posts, state.isRefreshing || state.home.mixedLoading,
                    state.errorMessage != null || state.home.mixedFeed?.page?.statuses.orEmpty().any {
                        it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                    }, { onAction(ForYouAction.OpenMixedFeed) }, {
                        onAction(ForYouAction.OpenMixedFeed)
                        onAction(ForYouAction.ShowHome)
                        onAction(ForYouAction.Refresh())
                    }, { onAction(ForYouAction.OpenMixedFeed) }) {
                    preview(null, posts)
                }
            }
            state.home.sections.forEach { section ->
                item(key = section.id) {
                    val posts = filterPosts(section.feed.page.items).take(10)
                    Column {
                        PostShelfSection(if (section.discovery) "Explore ${section.title}" else section.title,
                            null, posts, false, section.feed.page.statuses.any {
                                it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                            }, { onAction(ForYouAction.OpenSection(section.id)) }, { onAction(ForYouAction.RefreshSections) },
                            { onAction(ForYouAction.OpenSection(section.id)) }) {
                            preview(section.id, posts)
                        }
                        ForYouSectionActions(section, section.id !in state.home.pendingSectionIds, onAction)
                    }
                }
            }
            item(key = "status") {
                ForYouHomeFooter(state, onAction)
            }
        }
        }
}


@Composable
private fun ForYouHomeHeader(state: ForYouUiState, sourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit, onAction: (ForYouAction) -> Unit,
    reordering: Boolean, onReorder: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
        Text("For You", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterStart))
        Box(Modifier.align(Alignment.Center)) {
            ForYouSourceSelector(state.selectedSource, state.availableSources,
                !state.isRefreshing && !state.home.loading && !reordering,
                sourceMenu, onSourceMenuChange, {
                    onSourceMenuChange(false)
                    onAction(ForYouAction.SelectSource(it))
                })
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            IconButton(enabled = !state.home.loading && !state.home.addingSearch && state.home.pendingSectionIds.isEmpty(),
                onClick = onReorder) {
                Icon(if (reordering) Icons.Default.Check else Icons.Default.Reorder,
                    if (reordering) "Done reordering searches" else "Reorder searches")
            }
            IconButton(enabled = !reordering && !state.home.loading && !state.home.addingSearch && state.home.pendingSectionIds.isEmpty(),
                onClick = { onAction(ForYouAction.RefreshSections) }) {
                Icon(Icons.Default.Refresh, "Refresh For You")
            }
        }
    }
}

@Composable
private fun ForYouReorderList(
    state: ForYouUiState, ordering: ReorderListState<ForYouSection>, sourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit, onAction: (ForYouAction) -> Unit, onReorder: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
        userScrollEnabled = ordering.draggingId == null) {
        item { ForYouHomeHeader(state, sourceMenu, onSourceMenuChange, onAction, true, onReorder) }
        itemsIndexed(ordering.reorderDraft, key = { _, section -> section.id }) { index, section ->
            val dragging = ordering.draggingId == section.id
            Row(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { if (dragging) translationY = ordering.dragOffsetY }
                .onSizeChanged { ordering.itemHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .padding(horizontal = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ListReorderHandle(section.id, section.title, index, ordering)
            }
        }
    }
}

@Composable
private fun ForYouHomeFooter(state: ForYouUiState, onAction: (ForYouAction) -> Unit) {
    Column {
        ForYouHomeStatus(state, onAction)
        TextButton(enabled = !state.home.loading && !state.home.addingSearch && state.home.pendingSectionIds.isEmpty(),
            onClick = { onAction(ForYouAction.NewSection) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(if (state.home.addingSearch) "Creating search…" else "New search")
        }
    }
}

@Composable
private fun ForYouHomeStatus(state: ForYouUiState, onAction: (ForYouAction) -> Unit) {
    when {
        state.home.loading -> FeedLoadingState()
        state.home.error != null -> FeedErrorTile(state.home.error,
            onRetry = { onAction(ForYouAction.RefreshSections) })
        state.home.sections.isEmpty() -> Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Like posts to discover more interests. Hidden interests can be restored in Settings.")
            TextButton(onClick = { onAction(ForYouAction.GoToSearch) }) { Text("Go to Search") }
        }
    }
}

@Composable
private fun ForYouSectionActions(section: ForYouSection, enabled: Boolean, onAction: (ForYouAction) -> Unit) {
    var expanded by rememberSaveable(section.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.End) {
        IconButton(
            modifier = Modifier.expandableControlSemantics(expanded,
                if (expanded) "Expanded" else "Collapsed", { expanded = it }),
            onClick = { expanded = !expanded },
        ) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "${if (expanded) "Hide" else "Show"} actions for ${section.title}")
        }
        AnimatedVisibility(visible = expanded) {
            ForYouSectionActionButtons(section, enabled, onAction)
        }
    }
}

@Composable
private fun ForYouSectionActionButtons(section: ForYouSection, enabled: Boolean, onAction: (ForYouAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(enabled = enabled, onClick = { onAction(ForYouAction.PinSection(section.id)) }) {
            Icon(Icons.Default.PushPin, contentDescription = null)
            Text(if (section.pinned) "Unpin" else "Pin")
        }
        TextButton(enabled = enabled, onClick = { onAction(ForYouAction.SaveSection(section.id)) }) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = null)
            Text("Save to Codex")
        }
        TextButton(enabled = enabled, onClick = { onAction(ForYouAction.DismissSection(section.id)) }) {
            Icon(Icons.Default.Close, contentDescription = null)
            Text("Dismiss")
        }
    }
}

@Composable
private fun ForYouPreviewPosts(
    sectionId: String, posts: List<Post>, likedPostIds: Set<PostId>, pixivUgoiraClient: PixivUgoiraClient?,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onViewportChanged: (Post, Boolean) -> Unit, onDurationKnown: (Post, Long) -> Unit,
    onToggleLike: (Post) -> Unit, onLongPress: (Post) -> Unit, onOpen: (Int) -> Unit,
) {
    val row = rememberLazyListState()
    ViewerReturnShelf(ViewerReturnShelfId.ForYou(sectionId), posts.map { it.id }, row) {
    LazyRow(state = row, modifier = Modifier.height(164.dp / POST_SHELF_MEDIA_ASPECT_RATIO),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(posts, key = { _, post -> "${post.id.source}:${post.id.sourcePostId}" }) { index, post ->
            Box(Modifier.width(164.dp)) {
                SearchResultCard(post = post, liked = post.id in likedPostIds,
                    onToggleLike = { onToggleLike(post) }, onClick = { onOpen(index) },
                    onLongPress = { onLongPress(post) }, pixivUgoiraClient = pixivUgoiraClient,
                    showWatchHistory = true, showTitle = false, showSourceBadge = true,
                    mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO,
                    acquiredDurationMs = observedMediaDurationMs(post, durationStateForPost),
                    onViewportChanged = { onViewportChanged(post, it) },
                    onAuthoritativeDurationKnown = { onDurationKnown(post, it) })
            }
        }
    }
    }
}
