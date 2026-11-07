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
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.related.anchorIndexIn
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.unknownAnimatedDurationPolicy
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recents' related shelf. The tab on screen reports its posts through [showing]; switching tab or
 * source closes the shelf. Recents posts come from history, so shelf posts are never promoted into it.
 */
internal class RecentsRelatedViewModel(loader: RelatedPostsLoading) : ViewModel() {
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

    /** The posts the current view shows, in order; [key] names that view. */
    fun showing(key: String, posts: List<Post>) {
        if (shownKey != null && key != shownKey) related.clear("recents view changed")
        shownKey = key
        shownPosts = posts
    }

    companion object {
        fun factory(loader: RelatedPostsLoading) = viewModelFactory { initializer { RecentsRelatedViewModel(loader) } }
    }
}

/** The shelf a Recents view inserts below its anchor post, and the request that places it. */
internal class RecentsRelatedSlot(
    val request: RelatedPostsRequest,
    val content: @Composable () -> Unit,
)

/**
 * Reports the posts the selected Recents view shows to the shelf's owner, and builds the shelf slot
 * that view inserts while a shelf is open.
 */
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
    return rememberRecentsRelatedSlot(binding, shownPosts, cards, onLongPress)
}

/** Builds the slot for [binding] while it has a request; the shelf's cards share the Recents tiles' behavior. */
@Composable
private fun rememberRecentsRelatedSlot(
    binding: RelatedShelfBinding?,
    shownPosts: List<Post>,
    cards: RecentsPostCards,
    onLongPress: (Post) -> Unit,
): RecentsRelatedSlot? {
    val request = binding?.state?.requestOrNull ?: return null
    val environment = rememberFeedCardEnvironment(
        cards.pixivUgoiraClient, cards.likedPostIds, emptyMap(), cards.durationStateForPost, cards.onToggleLike,
        cards.onDurationPostVisibilityChanged, cards.onAuthoritativeDurationKnown,
    )
    val presentation = rememberRelatedFeedPresentation(
        canonicalPosts = shownPosts,
        visibleCanonicalPosts = shownPosts,
        relatedState = binding.state,
        visibilityFilters = SearchVisibilityFilters(),
        likedPostIds = cards.likedPostIds,
        savedPostIds = emptySet(),
        watchedPostIds = emptySet(),
        unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy(resolveInBackground = false),
        durationStates = emptyMap(),
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

/** The posts a Recents section shows, in order, as the shelf's owner anchors them. */
internal fun recentsShownPosts(entries: List<RecentPostEntry>): List<Post> = entries.map(RecentPostEntry::post).distinctBy(Post::id)

/** Inserts the shelf after the grid row of its anchor post. */
internal fun List<RecentsGridRow>.withRelatedShelf(request: RelatedPostsRequest?, posts: List<RecentPostEntry>): List<RecentsGridRow> {
    val anchor = request?.anchorIndexIn(posts.map { it.post.id }) ?: return this
    val row = indexOfFirst { it is RecentsGridRow.Post && it.index == anchor }
    if (row < 0) return this
    return toMutableList().apply { add(row + 1, RecentsGridRow.RelatedShelf) }
}

/** Inserts the shelf after the timeline row holding its anchor post. */
internal fun List<RecentsTimelineItem>.withRelatedShelf(request: RelatedPostsRequest?): List<RecentsTimelineItem> {
    request ?: return this
    val postIds: List<PostId> = filterIsInstance<RecentsTimelineItem.Posts>().flatMap { row -> row.entries.map { it.post.id } }.distinct()
    val anchorId = postIds.getOrNull(request.anchorIndexIn(postIds)) ?: return this
    val row = indexOfFirst { it is RecentsTimelineItem.Posts && it.entries.any { entry -> entry.post.id == anchorId } }
    if (row < 0) return this
    return toMutableList().apply { add(row + 1, RecentsTimelineItem.RelatedShelf) }
}

