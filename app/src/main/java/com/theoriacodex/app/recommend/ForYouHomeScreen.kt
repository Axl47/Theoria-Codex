package com.theoriacodex.app.recommend

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.search.SearchResultCard
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
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0), floatingActionButton = {
        FeedFilterFab(active = filtersActive, onClick = onShowFilters, contentDescription = "Filter and sort recommendations")
    }) { padding ->
        val shelfIds = listOf(null, ViewerReturnShelfId.ForYou("mixed")) +
            state.home.sections.map { ViewerReturnShelfId.ForYou(it.id) } + listOf(null)
        ViewerReturnColumn(shelfIds, scroll) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "header") {
                ForYouHomeHeader(state, sourceMenu, { sourceMenu = it }, onAction)
            }
            item(key = "mixed") {
                val posts = filterPosts(state.home.mixedFeed?.page?.items.orEmpty()).take(10)
                PostShelfSection("For You", null, posts, state.isRefreshing,
                    state.errorMessage != null || state.home.mixedFeed?.page?.statuses.orEmpty().any {
                        it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                    }, { onAction(ForYouAction.OpenMixedFeed) }, {
                        onAction(ForYouAction.OpenMixedFeed)
                        onAction(ForYouAction.ShowHome)
                        onAction(ForYouAction.Refresh())
                    }, { onAction(ForYouAction.OpenMixedFeed) }) {
                    ForYouPreviewPosts("mixed", posts, likedPostIds, pixivUgoiraClient, durationStateForPost,
                        onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike, onLongPress) { index ->
                        onOpenPreview(null, posts, index)
                    }
                }
            }
            state.home.sections.forEach { section ->
                item(key = section.id) {
                    val posts = filterPosts(section.feed.page.items).take(10)
                    Column {
                        Text(section.feed.seed.keys.joinToString { it.displayName() },
                            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PostShelfSection(if (section.discovery) "Explore ${section.title}" else section.title,
                            null, posts, false, section.feed.page.statuses.any {
                                it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
                            }, { onAction(ForYouAction.OpenSection(section.id)) }, { onAction(ForYouAction.RefreshSections) },
                            { onAction(ForYouAction.OpenSection(section.id)) }) {
                            ForYouPreviewPosts(section.id, posts, likedPostIds, pixivUgoiraClient, durationStateForPost,
                                onDurationPostVisibilityChanged, onAuthoritativeDurationKnown, onToggleLike, onLongPress) { index ->
                                onOpenPreview(section.id, posts, index)
                            }
                        }
                        ForYouSectionActions(section, section.id !in state.home.pendingSectionIds, onAction)
                    }
                }
            }
            item(key = "status") {
                ForYouHomeStatus(state, onAction)
            }
        }
        }
    }
}

@Composable
private fun ForYouHomeHeader(state: ForYouUiState, sourceMenu: Boolean,
    onSourceMenuChange: (Boolean) -> Unit, onAction: (ForYouAction) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("For You", style = MaterialTheme.typography.titleLarge)
            IconButton(enabled = !state.home.loading && state.home.pendingSectionIds.isEmpty(), onClick = { onAction(ForYouAction.RefreshSections) }) {
                Icon(Icons.Default.Refresh, "Shuffle interests")
            }
        }
        ForYouSourceSelector(state.selectedSource, state.availableSources, !state.isRefreshing,
            sourceMenu, onSourceMenuChange, {
                onSourceMenuChange(false)
                onAction(ForYouAction.SelectSource(it))
            })
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(enabled = enabled, onClick = { onAction(ForYouAction.PinSection(section.id)) }) {
            Icon(Icons.Default.PushPin, contentDescription = null)
            Text(if (section.pinned) "Unpin" else "Pin")
        }
        TextButton(enabled = enabled && !section.pinned, onClick = { onAction(ForYouAction.DismissSection(section.id)) }) {
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
