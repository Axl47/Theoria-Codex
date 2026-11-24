package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.codex.savedCodexChildIcon
import com.theoriacodex.app.codex.savedCodexCountSuffix
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.filterCodexCollectionPosts
import com.theoriacodex.app.codex.newFirst
import com.theoriacodex.app.ui.components.newPostsLabel
import com.theoriacodex.app.ui.rememberSavedNewPosts
import com.theoriacodex.app.ui.ReportLaunchContent
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.savedCollectionFilters
import com.theoriacodex.app.codex.savedCodexChildren
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.di.FeatureDependencies
import com.theoriacodex.app.codex.usesDuration
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.ui.components.LocalPostMenuActions
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.viewer.ViewerReturnColumn
import com.theoriacodex.app.viewer.ViewerReturnShelf
import com.theoriacodex.app.viewer.ViewerReturnShelfId
import com.theoriacodex.app.ui.components.PostShelfSection
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.POST_SHELF_CARD_WIDTH
import com.theoriacodex.app.ui.components.POST_SHELF_MEDIA_ASPECT_RATIO
import com.theoriacodex.app.ui.components.FeedFabRestoreRegistry
import com.theoriacodex.app.ui.components.codexFeedFabContext
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.domain.model.PostId

/**
 * Likes, Followed and saved Searches as shelves under a fixed [header]: Library's own in its Saved
 * section, [SavedBackHeader] on the standalone route.
 */
@Composable
internal fun SavedCodexRoute(
    data: DataDependencies,
    sources: SourceDependencies,
    features: FeatureDependencies,
    restoreRegistry: FeedFabRestoreRegistry,
    onOpenCodex: (String) -> Unit,
    onOpenViewer: (String, List<Post>, Int) -> Unit,
    onOpenCodexActions: (String) -> Unit,
    resolvePost: suspend (PostId) -> Post?,
    onGoToSearch: () -> Unit,
    header: @Composable () -> Unit,
) {
    val settings = browsingMetadataOwner(data).state.collectAsStateWithLifecycle().value?.settings ?: return
    val searches = rememberRetained("saved-searches") { data.savedSearches.observeSavedSearches() }.value ?: return
    val profileId = settings.activeRecommendationProfile().profileId
    val likesId = likesCodexIdForProfile(profileId)
    val likesCount = rememberRetained("likes:$profileId") { data.likesRepository.observeLikedPostIds(profileId) }.value?.size ?: return
    val counts = mapOf(likesId to likesCount, FOLLOWED_CODEX_ID to settings.followedCreators.size, SAVED_SEARCHES_CODEX_ID to searches.size)
    val news = rememberSavedNewPosts(data)
    val scroll = rememberLazyListState()
    val children = remember(likesId, news) { savedCodexChildren(likesId).newFirst(news) }
    val shelfIds = remember(children) { children.map { ViewerReturnShelfId.Saved(it.codexId) } }
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { scroll.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        header()
        ViewerReturnColumn(shelfIds, scroll) {
            LazyColumn(state = scroll, contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                children.forEach { child ->
                    item(key = child.codexId) {
                        SavedCodexShelf(child, counts.getValue(child.codexId), news[child.codexId].orEmpty(), data, sources, features, restoreRegistry, { scroll.isScrollInProgress },
                            SavedShelfActions({ onOpenCodex(child.codexId) }, { onOpenCodexActions(child.codexId) },
                                { posts, position -> onOpenViewer(child.codexId, posts, position) }, resolvePost, onGoToSearch))
                    }
                }
            }
        }
    }
}

/** The standalone Saved route's title row. */
@Composable
internal fun SavedBackHeader(onBack: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text("Saved", style = MaterialTheme.typography.titleLarge)
    }
}

/** What a Saved shelf can do: open its collection or its actions, open a post, or go to Search from its menu. */
private class SavedShelfActions(
    val open: () -> Unit,
    val openActions: () -> Unit,
    val openViewer: (List<Post>, Int) -> Unit,
    val resolvePost: suspend (PostId) -> Post?,
    val goToSearch: () -> Unit,
)

@Composable
private fun SavedCodexShelf(
    child: Codex,
    count: Int,
    news: Set<String>,
    data: DataDependencies,
    sources: SourceDependencies,
    features: FeatureDependencies,
    restoreRegistry: FeedFabRestoreRegistry,
    parentScrolling: () -> Boolean,
    actions: SavedShelfActions,
) {
    val restore = restoreRegistry.state(codexFeedFabContext(child.codexId)) ?: return
    val sort = CodexSortMode.entries.firstOrNull { it.name == restore.sortMode } ?: CodexSortMode.NEWEST_SAVED
    // Owned above the boundary so its known lengths reach the length sorts.
    val owner = viewModel<MediaDurationRouteViewModel>(key = "saved-duration:${child.codexId}",
        factory = MediaDurationRouteViewModel.factory(features.mediaDurationCoordinator, "saved:${child.codexId}"))
    val durationStates by owner.states.collectAsStateWithLifecycle()
    CodexDetailDestinationStateBoundary(child.codexId, sort, data, sources, features, restore, durationStates) { state ->
        SavedCodexSection(state, child.codexId, child.name, count, news, restore, sort,
            owner, durationStates, sources, parentScrolling, actions)
    }
}

@Composable
private fun SavedCodexSection(
    state: CodexDetailDestinationState,
    id: String,
    title: String,
    count: Int,
    news: Set<String>,
    restore: FeedFabRestoreState,
    sort: CodexSortMode,
    owner: MediaDurationRouteViewModel,
    durationStates: MediaDurationStates,
    sources: SourceDependencies,
    parentScrolling: () -> Boolean,
    actions: SavedShelfActions,
) {
    SideEffect { owner.synchronize(id, state.posts, state.resolveUnknownAnimatedDurations) }
    val filters = remember(restore) { restore.savedCollectionFilters() }
    val metadata = rememberDurationFilterMetadata(state.posts, durationStates, !filters.animatedDurationRange.isFullRange)
    // New posts lead the strip.
    val posts = remember(state.posts, filters, metadata.knownDurationMsByPostId, news) {
        filterCodexCollectionPosts(state.posts, filters, metadata.knownDurationMsByPostId)
            .sortedByDescending { it.id.markKey() in news }
    }
    SideEffect { owner.onFilterChanged(!filters.animatedDurationRange.isFullRange || sort.usesDuration) }
    val cardWidth = POST_SHELF_CARD_WIDTH
    val rowHeight = cardWidth / POST_SHELF_MEDIA_ASPECT_RATIO
    val row = rememberLazyListState()
    DurationRouteEnvironmentEffect({ parentScrolling() || row.isScrollInProgress }, owner::onEnvironmentChanged)
    val liveOwner = state.followedOwner ?: state.savedSearchOwner
    val feed = liveOwner?.state?.collectAsStateWithLifecycle()?.value
    ReportLaunchContent(TopLevelDestination.Library, drawn = posts.isNotEmpty() ||
        if (liveOwner == null) !state.loading else feed?.errors?.isNotEmpty() == true)
    val postIds = remember(posts) { posts.map { it.id } }
    // Followed and saved-search sections are live feeds, so only their card hearts like; the hold menu, like Codex
    // detail's, can also unlike from Likes.
    val durations = remember(owner, durationStates) { owner.feedDurations(durationStates, state.resolveUnknownAnimatedDurations) }
    val cards = rememberFeedCardEnvironment(
        sources.pixivUgoiraClient, state.likedPostIds, emptyMap(), durations,
        LocalPostMenuActions.current.toggleLike.takeIf { liveOwner != null }, actions.resolvePost,
    )
    var menuPost by remember { mutableStateOf<Post?>(null) }
    ViewerReturnShelf(ViewerReturnShelfId.Saved(id), postIds, row) {
        PostShelfSection(title, count, posts, state.loading, feed?.errors?.isNotEmpty() == true,
            actions.open, { liveOwner?.retry() }, actions.openActions,
            icon = savedCodexChildIcon(id),
            countSuffix = savedCodexCountSuffix(id, count),
            onMore = actions.openActions,
            highlight = newPostsLabel(news.size)) {
            LazyRow(state = row, modifier = Modifier.height(rowHeight), contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(posts, key = { _, post -> "${post.id.source.id}:${post.id.sourcePostId}" }) { index, post ->
                    Box(Modifier.width(cardWidth)) {
                        FeedPostCard(post = post, environment = cards,
                            onClick = { actions.openViewer(posts, index) }, onLongPress = { menuPost = post },
                            showSourceBadge = true, showTitle = false,
                            mediaAspectRatio = POST_SHELF_MEDIA_ASPECT_RATIO)
                    }
                }
                if (feed?.canLoadMore == true) item(key = "more") { TextButton(onClick = actions.open) { Text("View all") } }
            }
        }
    }
    menuPost?.let { post -> FeedPostActionSheet(post, { menuPost = null }, actions.goToSearch) }
}
