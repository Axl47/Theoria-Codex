package com.theoriacodex.app.related

import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

/**
 * A feed a Viewer session can be launched from and keep following: Search, For You and a Creator page.
 *
 * The shell finds the owner of a session's [streamSource] and, through this interface alone, extends the
 * session with what the feed loads, asks it for the next page, drives its related shelf from the Viewer,
 * and hands it posts the Viewer resolved.
 */
interface ViewerFeedOwner {
    val streamSource: ViewerStreamSource

    /** The feed as a live Viewer session reads it now. */
    val viewerFeed: ViewerFeedSnapshot

    /** [viewerFeed] each time it changes. */
    val viewerFeedChanges: Flow<ViewerFeedSnapshot>

    /** The feed's related shelf, which the Viewer shows as its strip. */
    val related: RelatedPostsRouteController

    fun loadNextPage()

    /** Replaces the feed's copy of [post] with the fuller one the Viewer resolved. */
    fun rememberResolvedPost(post: Post)
}

/** What a live Viewer session reads from its feed; [queryHash] tells the session whether the feed still shows its query. */
data class ViewerFeedSnapshot(
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val retainedLikedPostIds: Set<PostId> = emptySet(),
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
)
