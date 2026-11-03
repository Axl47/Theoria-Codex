package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.codex.savedCodexChildIcon
import com.theoriacodex.app.codex.savedCodexCountSuffix
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.codex.filterCodexCollectionPosts
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.savedCollectionFilters
import com.theoriacodex.app.codex.savedCodexChildren
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.viewer.ViewerReturnColumn
import com.theoriacodex.app.viewer.ViewerReturnShelf
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.ui.components.PostShelfSection
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.POST_SHELF_CARD_WIDTH
import com.theoriacodex.app.ui.components.POST_SHELF_MEDIA_ASPECT_RATIO
import com.theoriacodex.app.ui.components.FeedFabRestoreRegistry
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

@Composable
internal fun SavedCodexRoute(
    data: DataDependencies,
    sources: SourceDependencies,
    durations: MediaDurationCoordinator,
    restoreRegistry: FeedFabRestoreRegistry,
    onBack: () -> Unit,
    onOpenCodex: (String) -> Unit,
    onOpenViewer: (String, List<Post>, Int) -> Unit,
    onOpenCodexActions: (String) -> Unit,
    resolvePost: suspend (PostId) -> Post?,
    onToggleLike: (Post) -> Unit = {},
) {
    val settings = browsingMetadataOwner(data).state.collectAsStateWithLifecycle().value?.settings ?: return
    val searches = rememberRetained("saved-searches") { data.savedSearches.observeSavedSearches() }.value ?: return
    val profileId = settings.activeRecommendationProfile().profileId
    val likesId = likesCodexIdForProfile(profileId)
    val likesCount = rememberRetained("likes:$profileId") { data.likesRepository.observeLikes(profileId) }.value?.size ?: return
    val counts = listOf(likesCount, settings.followedCreators.size, searches.size)
    val scroll = rememberLazyListState()
    val children = remember(likesId) { savedCodexChildren(likesId) }
    val shelfIds = remember(children) { listOf(null) + children.map { ViewerReturnShelfId.Saved(it.codexId) } }
    ViewerReturnColumn(shelfIds, scroll) {
        LazyColumn(state = scroll, contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Saved", style = MaterialTheme.typography.titleLarge)
                }
            }
            children.forEachIndexed { index, child ->
                item(key = child.codexId) {
                    val restore = restoreRegistry.state(codexFeedFabContext(child.codexId)) ?: return@item
                    val sort = CodexSortMode.entries.firstOrNull { it.name == restore.sortMode }
                        ?: CodexSortMode.NEWEST_SAVED
                    CodexDetailDestinationStateBoundary(child.codexId, sort, data, sources, restore) { state ->
                        SavedCodexSection(state, child.codexId, child.name, counts[index], restore,
                            durations, sources, { scroll.isScrollInProgress },
                            { onOpenCodex(child.codexId) },
                            { onOpenCodexActions(child.codexId) },
                            { posts, position -> onOpenViewer(child.codexId, posts, position) }, resolvePost, onToggleLike)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedCodexSection(
    state: CodexDetailDestinationState,
    id: String,
    title: String,
    count: Int,
    restore: FeedFabRestoreState,
    durations: MediaDurationCoordinator,
    sources: SourceDependencies,
    parentScrolling: () -> Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onOpenViewer: (List<Post>, Int) -> Unit,
    resolvePost: suspend (PostId) -> Post?,
    onToggleLike: (Post) -> Unit,
) {
    val owner = viewModel<MediaDurationRouteViewModel>(key = "saved-duration:$id",
        factory = MediaDurationRouteViewModel.factory(durations, "saved:$id"))
    SideEffect { owner.synchronize(id, state.posts, state.resolveUnknownAnimatedDurations) }
    val durationStates by owner.states.collectAsStateWithLifecycle()
    val filters = remember(restore) { restore.savedCollectionFilters() }
    val metadata = rememberDurationFilterMetadata(state.posts, durationStates, !filters.animatedDurationRange.isFullRange)
    val posts = remember(state.posts, filters, metadata.knownDurationMsByPostId) {
        filterCodexCollectionPosts(state.posts, filters, UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
            metadata.knownDurationMsByPostId)
    }
    SideEffect { owner.onFilterChanged(!filters.animatedDurationRange.isFullRange) }
    val cardWidth = POST_SHELF_CARD_WIDTH
    val rowHeight = cardWidth / POST_SHELF_MEDIA_ASPECT_RATIO
    val row = rememberLazyListState()
    DurationRouteEnvironmentEffect({ parentScrolling() || row.isScrollInProgress }, owner::onEnvironmentChanged)
    val liveOwner = state.followedOwner ?: state.savedSearchOwner
    val feed = liveOwner?.state?.collectAsStateWithLifecycle()?.value
    val postIds = remember(posts) { posts.map { it.id } }
    ViewerReturnShelf(ViewerReturnShelfId.Saved(id), postIds, row) {
        PostShelfSection(title, count, posts, state.loading, feed?.errors?.isNotEmpty() == true,
            onOpen, { liveOwner?.retry() }, onLongPress,
            icon = savedCodexChildIcon(id),
            countSuffix = savedCodexCountSuffix(id),
            onMore = onLongPress) {
            LazyRow(state = row, modifier = Modifier.height(rowHeight), contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(posts, key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" }) { index, post ->
                    Box(Modifier.width(cardWidth)) {
                        SearchResultCard(post = post,
                            liked = post.id in state.likedPostIds,
                            onToggleLike = if (state.followedOwner != null || state.savedSearchOwner != null) ({ onToggleLike(post) }) else null,
                            pixivUgoiraClient = sources.pixivUgoiraClient,
                            mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO,
                            showTitle = false,
                            acquiredDurationMs = observedMediaDurationMs(post, owner::observeState),
                            showSourceBadge = true, resolvePostById = resolvePost,
                            onClick = { onOpenViewer(posts, index) },
                            onViewportChanged = { owner.onPostVisibilityChanged(post, it) },
                            onAuthoritativeDurationKnown = { owner.publishPlayerDuration(post, it) })
                    }
                }
                if (feed?.canLoadMore == true) item(key = "more") { TextButton(onClick = onOpen) { Text("View all") } }
            }
        }
    }
}
