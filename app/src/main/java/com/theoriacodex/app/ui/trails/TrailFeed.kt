package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.trails.TrailPager
import com.theoriacodex.app.trails.nextOrNull
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    override val related = RelatedPostsRouteController(
        scope, relatedLoader, currentState = { RelatedPostsUiState.Idle }, canonicalPosts = { snapshot.value.results }, updateState = {},
    )

    suspend fun start(visible: (List<Post>) -> List<Post> = { it }): List<Post> {
        repeat(FIRST_PAGE_LIMIT) {
            if (!step()) return emptyList()
            val shown = visible(snapshot.value.results)
            if (shown.isNotEmpty() || !snapshot.value.canLoadMore) return shown
        }
        return emptyList()
    }

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

    private suspend fun step(): Boolean {
        val page = pager.nextOrNull()
        snapshot.update { feed ->
            if (page == null) feed.copy(loadingMore = false) else feed.copy(results = page.posts, canLoadMore = page.canLoadMore, loadingMore = false)
        }
        return page != null
    }

    private companion object {
        const val FIRST_PAGE_LIMIT = 3
    }
}
