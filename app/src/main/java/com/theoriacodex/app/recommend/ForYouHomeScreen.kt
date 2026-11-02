package com.theoriacodex.app.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.theoriacodex.app.ui.components.ActionMenuDetailText
import com.theoriacodex.app.ui.components.ActionMenuDivider
import com.theoriacodex.app.ui.components.ActionMenuHeader
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.app.ui.components.PostThumbnail
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.BookmarkAdd
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
    filterLabels: List<String>,
    onClearFilters: () -> () -> Unit,
    onOpenPreview: (String?, List<Post>, Int) -> Unit,
) {
    val scroll = rememberLazyListState()
    var sourceMenu by remember { mutableStateOf(false) }
    val ordering = remember { ReorderListState(state.home.sections, ForYouSection::id) }
    LaunchedEffect(state.home.sections) { ordering.synchronize(state.home.sections) }
    val onReorder = {
        ordering.toggle(state.home.sections) { onAction(ForYouAction.ReorderSections(it)) }
    }
    var homeMenu by remember { mutableStateOf(false) }
    var sectionMenuId by remember { mutableStateOf<String?>(null) }
    val filterScroll = rememberFeedScrollSignal()
    if (homeMenu) {
        ForYouHomeMenu(state, onAction, onReorder = onReorder, onDismiss = { homeMenu = false })
    }
    // Looked up live so the menu reflects Pin and disappears if its section does.
    state.home.sections.firstOrNull { it.id == sectionMenuId }?.let { section ->
        ForYouSectionMenu(section, section.id !in state.home.pendingSectionIds, onAction) { sectionMenuId = null }
    }
    Scaffold(modifier = Modifier.nestedScroll(filterScroll.connection), contentWindowInsets = WindowInsets(0), floatingActionButton = {
        FeedFilterFab(filterLabels, "Filter and sort recommendations", onShowFilters, onClearFilters, scrollSignal = filterScroll)
    }) { padding ->
        if (ordering.reorderMode) {
            ForYouReorderList(state, ordering, sourceMenu, { sourceMenu = it }, onAction, onReorder,
                Modifier.padding(padding))
        } else {
            ForYouOverview(state, scroll, Modifier.padding(padding), filterPosts, onAction,
                onSectionMenu = { sectionMenuId = it },
                header = { ForYouHomeHeader(state, sourceMenu, { sourceMenu = it }, onAction, false, onReorder) { homeMenu = true } },
                preview = { id, posts ->
                    ForYouPreviewPosts(id ?: "mixed", if (id == null) MIX_CARD_WIDTH else SHELF_CARD_WIDTH, posts, likedPostIds, pixivUgoiraClient, durationStateForPost,
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
    onSectionMenu: (String) -> Unit,
    header: @Composable () -> Unit, preview: @Composable (String?, List<Post>) -> Unit,
) {
        val coroutineScope = rememberCoroutineScope()
        val hasChips = state.home.sections.isNotEmpty()
        val firstSectionIndex = if (hasChips) 3 else 2
        val shelfIds = listOf<ViewerReturnShelfId?>(null) + (if (hasChips) listOf(null) else emptyList()) +
            listOf(ViewerReturnShelfId.ForYou("mixed")) +
            state.home.sections.map { ViewerReturnShelfId.ForYou(it.id) } + listOf(null)
        ViewerReturnColumn(shelfIds, scroll) {
        LazyColumn(state = scroll, modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "header") {
                header()
            }
            if (hasChips) {
                item(key = "interests") {
                    ForYouInterestChips(state.home.sections) { index ->
                        coroutineScope.launch { scroll.animateScrollToItem(firstSectionIndex + index) }
                    }
                }
            }
            item(key = "mixed") {
                val posts = filterPosts(state.home.mixedFeed?.page?.items.orEmpty()).take(10)
                PostShelfSection("Your mix", null, posts, state.isRefreshing || state.home.mixedLoading,
                    state.errorMessage != null || state.home.mixedFeed?.page?.statuses.orEmpty().any {
                        it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                    }, { onAction(ForYouAction.OpenMixedFeed) }, {
                        onAction(ForYouAction.OpenMixedFeed)
                        onAction(ForYouAction.ShowHome)
                        onAction(ForYouAction.Refresh())
                    }, { onAction(ForYouAction.OpenMixedFeed) }, showSeeAll = true) {
                    preview(null, posts)
                }
            }
            state.home.sections.forEach { section ->
                item(key = section.id) {
                    val posts = filterPosts(section.feed.page.items).take(10)
                    PostShelfSection(section.title,
                        null, posts, false, section.feed.page.statuses.any {
                            it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                        }, { onAction(ForYouAction.OpenSection(section.id)) }, { onAction(ForYouAction.RefreshSections) },
                        { onSectionMenu(section.id) },
                        icon = section.kindIcon(),
                        countSuffix = section.kindLabel(),
                        onMore = { onSectionMenu(section.id) }) {
                        preview(section.id, posts)
                    }
                }
            }
            item(key = "status") {
                ForYouHomeStatus(state, onAction)
            }
        }
        }
}


@Composable
private fun ForYouHomeHeader(state: ForYouUiState, sourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit, onAction: (ForYouAction) -> Unit,
    reordering: Boolean, onReorder: () -> Unit, onOpenMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (reordering) "Reorder" else "For You", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Box {
            ForYouSourceSelector(state.selectedSource, state.availableSources,
                !state.isRefreshing && !state.home.loading && !reordering,
                sourceMenu, onSourceMenuChange, {
                    onSourceMenuChange(false)
                    onAction(ForYouAction.SelectSource(it))
                })
        }
        if (reordering) {
            IconButton(onClick = onReorder) { Icon(Icons.Default.Check, "Done reordering searches") }
        } else {
            IconButton(enabled = !state.home.loading && !state.home.addingSearch && state.home.pendingSectionIds.isEmpty(),
                onClick = { onAction(ForYouAction.RefreshSections) }) {
                Icon(Icons.Default.Refresh, "Refresh For You")
            }
            IconButton(onClick = onOpenMenu) { Icon(Icons.Default.MoreVert, "For You actions") }
        }
    }
}

/** Jumps to an interest without scrolling past every shelf above it. */
@Composable
private fun ForYouInterestChips(sections: List<ForYouSection>, onJump: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(sections, key = { _, section -> section.id }) { index, section ->
            AssistChip(
                onClick = { onJump(index) },
                label = { Text(section.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = section.kindIcon()?.let { icon -> { Icon(icon, null, Modifier.size(16.dp)) } },
            )
        }
    }
}

private fun ForYouSection.kindIcon(): ImageVector? = when {
    pinned -> Icons.Default.Bookmark
    discovery -> Icons.Default.AutoAwesome
    else -> null
}

private fun ForYouSection.kindLabel(): String? = when {
    pinned -> "Saved"
    discovery -> "Explore"
    else -> null
}

@Composable
private fun ForYouHomeMenu(
    state: ForYouUiState, onAction: (ForYouAction) -> Unit, onReorder: () -> Unit, onDismiss: () -> Unit,
) {
    val idle = !state.home.loading && !state.home.addingSearch && state.home.pendingSectionIds.isEmpty()
    ActionMenuSheet(onDismiss) {
        ActionMenuRow(Icons.Default.Add, if (state.home.addingSearch) "Creating search…" else "New search", idle) {
            onDismiss()
            onAction(ForYouAction.NewSection)
        }
        ActionMenuRow(Icons.Default.Reorder, "Reorder", idle) {
            onDismiss()
            onReorder()
        }
    }
}

@Composable
private fun ForYouSectionMenu(
    section: ForYouSection, enabled: Boolean, onAction: (ForYouAction) -> Unit, onDismiss: () -> Unit,
) {
    val cover = section.feed.page.items.firstOrNull()
    fun act(action: ForYouAction) {
        onDismiss()
        onAction(action)
    }
    ActionMenuSheet(onDismiss) {
        ActionMenuHeader(
            title = section.title,
            image = { modifier -> if (cover != null) PostThumbnail(cover, modifier) else Box(modifier) },
            details = { ActionMenuDetailText(section.kindLabel() ?: "Interest") },
        )
        ActionMenuRow(Icons.Default.Bookmark, if (section.pinned) "Remove from Saved" else "Save search", enabled) {
            act(ForYouAction.PinSection(section.id))
        }
        ActionMenuRow(Icons.Default.BookmarkAdd, "Save and hide here", enabled) { act(ForYouAction.SaveSection(section.id)) }
        ActionMenuDivider()
        ActionMenuRow(Icons.Default.Close, "Dismiss", enabled, MaterialTheme.colorScheme.error) {
            act(ForYouAction.DismissSection(section.id))
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
        item { ForYouHomeHeader(state, sourceMenu, onSourceMenuChange, onAction, true, onReorder) {} }
        itemsIndexed(ordering.reorderDraft, key = { _, section -> section.id }) { index, section ->
            val dragging = ordering.draggingId == section.id
            Row(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { if (dragging) translationY = ordering.dragOffsetY }
                .onSizeChanged { ordering.itemHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .padding(horizontal = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val thumbnail = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                section.feed.page.items.firstOrNull()?.let { PostThumbnail(it, thumbnail) } ?: Box(thumbnail)
                section.kindIcon()?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ListReorderHandle(section.id, section.title, index, ordering)
            }
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
private fun ForYouPreviewPosts(
    sectionId: String, cardWidth: Dp, posts: List<Post>, likedPostIds: Set<PostId>, pixivUgoiraClient: PixivUgoiraClient?,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onViewportChanged: (Post, Boolean) -> Unit, onDurationKnown: (Post, Long) -> Unit,
    onToggleLike: (Post) -> Unit, onLongPress: (Post) -> Unit, onOpen: (Int) -> Unit,
) {
    val row = rememberLazyListState()
    ViewerReturnShelf(ViewerReturnShelfId.ForYou(sectionId), posts.map { it.id }, row) {
    LazyRow(state = row, modifier = Modifier.height(cardWidth / POST_SHELF_MEDIA_ASPECT_RATIO),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(posts, key = { _, post -> "${post.id.source}:${post.id.sourcePostId}" }) { index, post ->
            Box(Modifier.width(cardWidth)) {
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

private val SHELF_CARD_WIDTH = 164.dp
private val MIX_CARD_WIDTH = 220.dp
