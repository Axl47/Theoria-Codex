package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A trail's posts so far, and whether it can give more. */
internal data class TrailPage(val posts: List<Post>, val canLoadMore: Boolean)

/** A trail's source of posts: each call loads one more page and returns everything so far. Throws when a page fails. */
internal fun interface TrailPager {
    suspend fun next(): TrailPage
}

/**
 * A trail fork's feed, which the Viewer level showing it follows as stream [ViewerStreamSource.TRAIL]. [start] loads the
 * first page before the fork opens; afterwards it pages as the Viewer asks. [close] it when its Viewer level is gone.
 */
internal class TrailFeed(
    val queryHash: String,
    private val pager: TrailPager,
    relatedLoader: RelatedPostsLoading,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewerFeedOwner {
    private val snapshot = MutableStateFlow(ViewerFeedSnapshot(queryHash = queryHash))

    override val streamSource = ViewerStreamSource.TRAIL
    override val viewerFeed: ViewerFeedSnapshot get() = snapshot.value
    override val viewerFeedChanges: Flow<ViewerFeedSnapshot> = snapshot

    // Trails keep no related shelf: Similar is itself a trail.
    override val related = RelatedPostsRouteController(
        scope, relatedLoader, currentState = { RelatedPostsUiState.Idle }, canonicalPosts = { snapshot.value.results }, updateState = {},
    )

    /** Loads the first page; true when it holds posts to show. */
    suspend fun start(): Boolean = step() && snapshot.value.results.isNotEmpty()

    override fun loadNextPage() {
        val current = snapshot.value
        if (!current.canLoadMore || current.loadingMore) return
        snapshot.value = current.copy(loadingMore = true)
        scope.launch { step() }
    }

    override fun rememberResolvedPost(post: Post) {
        snapshot.update { feed -> feed.copy(results = feed.results.map { if (it.id == post.id) post else it }) }
    }

    fun close() = scope.cancel()

    /** One page; a failure keeps what was loaded, so the Viewer can ask again later. */
    private suspend fun step(): Boolean = try {
        val page = pager.next()
        snapshot.update { it.copy(results = page.posts, canLoadMore = page.canLoadMore, loadingMore = false) }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        snapshot.update { it.copy(loadingMore = false) }
        false
    }
}
