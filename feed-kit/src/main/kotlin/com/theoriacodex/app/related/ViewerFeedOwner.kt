package com.theoriacodex.app.related

import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

interface ViewerFeedOwner {
    val streamSource: ViewerStreamSource

    val viewerFeed: ViewerFeedSnapshot

    val viewerFeedChanges: Flow<ViewerFeedSnapshot>

    val related: RelatedPostsRouteController

    fun loadNextPage()

    fun retryPage() = loadNextPage()

    fun rememberResolvedPost(post: Post)
}

data class ViewerFeedSnapshot(
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val pageFailed: Boolean = false,
    val retainedLikedPostIds: Set<PostId> = emptySet(),
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
)
