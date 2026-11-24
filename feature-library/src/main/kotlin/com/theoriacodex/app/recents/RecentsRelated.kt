package com.theoriacodex.app.recents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.related.anchorIndexIn
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecentsRelatedViewModel(loader: RelatedPostsLoading) : ViewModel() {
    private val mutableState = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
    val state = mutableState.asStateFlow()
    private var shownKey: String? = null
    private var shownPosts: List<Post> = emptyList()
    val related = RelatedPostsRouteController(
        scope = viewModelScope,
        loader = loader,
        currentState = { mutableState.value },
        canonicalPosts = { shownPosts },
        updateState = { mutableState.value = it },
    )

    fun showing(key: String, posts: List<Post>) {
        if (shownKey != null && key != shownKey) related.clear("recents view changed")
        shownKey = key
        shownPosts = posts
    }

    companion object {
        fun factory(loader: RelatedPostsLoading) = viewModelFactory { initializer { RecentsRelatedViewModel(loader) } }
    }
}

internal class RecentsRelatedSlot(
    val request: RelatedPostsRequest,
    val content: @Composable () -> Unit,
)

@Composable
internal fun rememberRecentsRelated(
    binding: RelatedShelfBinding?,
    filter: RecentsFilter,
    source: SourceKey?,
    visible: VisibleRecents,
    cards: RecentsPostCards,
    onShownPostsChanged: (key: String, posts: List<Post>) -> Unit,
    onLongPress: (Post) -> Unit,
): RecentsRelatedSlot? {
    val shownPosts = remember(filter, visible) {
        when (filter) {
            RecentsFilter.ALL -> recentsShownPosts(visible.activity.filterIsInstance<RecentActivityEntry.Watched>().map { it.entry })
            RecentsFilter.WATCHED -> recentsShownPosts(visible.watchedPosts)
            RecentsFilter.CODEX -> recentsShownPosts(visible.codexPosts)
            RecentsFilter.SEARCHES -> emptyList()
        }
    }
    val key = "$filter:${source?.id}"
    val currentOnShownPostsChanged by rememberUpdatedState(onShownPostsChanged)
    LaunchedEffect(key, shownPosts) { currentOnShownPostsChanged(key, shownPosts) }
    return rememberRecentsRelatedSlot("recents:$key", binding, shownPosts, cards, onLongPress)
}

@Composable
private fun rememberRecentsRelatedSlot(
    retainKey: String,
    binding: RelatedShelfBinding?,
    shownPosts: List<Post>,
    cards: RecentsPostCards,
    onLongPress: (Post) -> Unit,
): RecentsRelatedSlot? {
    val request = binding?.state?.requestOrNull ?: return null
    val environment = cards.environment
    val presentation = rememberRelatedFeedPresentation(
        retainKey = retainKey,
        canonicalPosts = shownPosts,
        visibleCanonicalPosts = shownPosts,
        relatedState = binding.state,
        visibilityFilters = SearchVisibilityFilters(),
        likedPostIds = environment.likedPostIds.value,
        savedPostIds = emptySet(),
        watchedPostIds = emptySet(),
        durationStates = MediaDurationStates.EMPTY,
        durationFilterActive = false,
    )
    val controller = binding.controller
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return remember(request, binding, presentation, environment) {
        RecentsRelatedSlot(request) {
            FeedRelatedPostsShelf(
                state = binding.state,
                presentation = presentation,
                environment = environment,
                onOpenPost = binding.onOpenPost,
                onLongPress = { post -> currentOnLongPress(post) },
                onDismiss = { controller.clear() },
                onRetry = controller::retry,
                onPreviousPage = controller::showPreviousPage,
                onNextPage = controller::showNextPage,
            )
        }
    }
}

internal fun recentsShownPosts(entries: List<RecentPostEntry>): List<Post> = entries.map(RecentPostEntry::post).distinctBy(Post::id)

internal fun List<RecentsGridRow>.withRelatedShelf(request: RelatedPostsRequest?, posts: List<RecentPostEntry>): List<RecentsGridRow> {
    val anchor = request?.anchorIndexIn(posts.map { it.post.id }) ?: return this
    val row = indexOfFirst { it is RecentsGridRow.Post && it.index == anchor }
    if (row < 0) return this
    return toMutableList().apply { add(row + 1, RecentsGridRow.RelatedShelf) }
}

internal fun List<RecentsTimelineItem>.withRelatedShelf(request: RelatedPostsRequest?): List<RecentsTimelineItem> {
    request ?: return this
    val postIds: List<PostId> = filterIsInstance<RecentsTimelineItem.Posts>().flatMap { row -> row.entries.map { it.post.id } }.distinct()
    val anchorId = postIds.getOrNull(request.anchorIndexIn(postIds)) ?: return this
    val row = indexOfFirst { it is RecentsTimelineItem.Posts && it.entries.any { entry -> entry.post.id == anchorId } }
    if (row < 0) return this
    return toMutableList().apply { add(row + 1, RecentsTimelineItem.RelatedShelf) }
}

