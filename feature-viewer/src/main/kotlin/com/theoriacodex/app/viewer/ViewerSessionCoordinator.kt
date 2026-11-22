package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.keepingListingDataOf
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CommentTarget
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID

data class ViewerRelatedBinding(val source: ViewerStreamSource, val queryHash: String)

/**
 * One Viewer launch: what was opened and where from. Its [posts] are the launch payload and never change; the
 * live post list, which grows with the feed and the related shelf, is [com.theoriacodex.app.viewer.state.ViewerUiState.pages].
 */
data class ViewerSession(
    val posts: List<Post>,
    val context: ViewerLaunchContext,
    val liveSearchBinding: Boolean = false,
    val searchVisibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    val sessionId: String = UUID.randomUUID().toString(),
    val initialMediaIndices: Map<com.theoriacodex.domain.model.PostId, Int> = emptyMap(),
    // Transient launch-owner action; cold restoration never invents a related-shelf owner.
    val onToggleLike: ((Post) -> Unit)? = null,
    // Captured applied query for contextual save ranking; never inferred from another live route.
    val saveQuery: com.theoriacodex.domain.model.Query? = null,
    val relatedBinding: ViewerRelatedBinding? = null,
    // A comment link's target on the first post; the Viewer opens its comments on it once, when the session launches.
    val focusComment: CommentTarget? = null,
    // The mode a series session's chapters open in, known before the first one draws.
    val seriesReadingMode: com.theoriacodex.domain.model.GalleryReadingMode? = null,
)

internal fun requiresViewerPostResolution(post: Post, streamSource: ViewerStreamSource): Boolean {
    if (requiresLazyMediaResolution(post)) return true
    if (
        streamSource == ViewerStreamSource.CODEX ||
        streamSource == ViewerStreamSource.RECENTS
    ) {
        return post.id.source.hasRefreshableRemoteMedia() &&
            hasRemoteViewerMedia(post)
    }
    return false
}

internal fun requiresPrelaunchViewerPostResolution(post: Post): Boolean {
    return !hasUsableViewerMedia(post)
}

internal fun hasUsableViewerMedia(post: Post): Boolean {
    return sequence {
        yieldAll(post.media)
        post.full?.let { yield(it) }
        yield(post.preview)
    }.any { media ->
        !media.localPath.isNullOrBlank() ||
            !media.url.isNullOrBlank() ||
            media.progressiveUrls.any { location -> location.isNotBlank() } ||
            media.fallbackUrls.any { location -> location.isNotBlank() }
    }
}

private fun hasRemoteViewerMedia(post: Post): Boolean {
    val primaryMedia = buildList {
        addAll(post.media)
        post.full?.let { add(it) }
    }.ifEmpty { listOf(post.preview) }
    return primaryMedia.any { ref ->
        !ref.url.isNullOrBlank() && ref.localPath.isNullOrBlank()
    }
}

suspend fun prepareViewerPostsForLaunch(
    posts: List<Post>,
    context: ViewerLaunchContext,
    resolvePost: suspend (Post) -> Post?,
): List<Post> {
    if (posts.isEmpty()) return posts
    val startIndex = context.startIndex.coerceIn(0, posts.lastIndex)
    val selectedPost = posts[startIndex]
    if (!requiresPrelaunchViewerPostResolution(selectedPost)) return posts
    val resolved = resolvePost(selectedPost)?.keepingListingDataOf(selectedPost) ?: return posts
    return posts.toMutableList().apply { this[startIndex] = resolved }
}

/** The posts of [incoming] that [current] lacks, in order and without repeats. */
fun newViewerPosts(current: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return emptyList()
    val seen = current.mapTo(mutableSetOf()) { post -> "${post.id.source.id}:${post.id.sourcePostId}" }
    return incoming.filter { post -> seen.add("${post.id.source.id}:${post.id.sourcePostId}") }
}
