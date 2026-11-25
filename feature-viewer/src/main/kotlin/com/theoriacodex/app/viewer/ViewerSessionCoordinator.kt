package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.keepingListingDataOf
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CommentTarget
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID

data class ViewerRelatedBinding(val source: ViewerStreamSource, val queryHash: String)

data class ViewerSession(
    val posts: List<Post>,
    val context: ViewerLaunchContext,
    val liveSearchBinding: Boolean = false,
    val searchVisibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    val sessionId: String = UUID.randomUUID().toString(),
    val initialMediaIndices: Map<com.theoriacodex.domain.model.PostId, Int> = emptyMap(),
    val onToggleLike: ((Post) -> Unit)? = null,
    val saveQuery: com.theoriacodex.domain.model.Query? = null,
    val relatedBinding: ViewerRelatedBinding? = null,
    val focusComment: CommentTarget? = null,
    val series: ViewerSeries? = null,
    val stripMarks: Map<com.theoriacodex.domain.model.PostId, ViewerStripMark> = emptyMap(),
)

data class ViewerPageFraction(val page: Int, val fraction: Float)

sealed interface ViewerSeries {
    data class Reading(val mode: GalleryReadingMode, val pageFractions: Map<PostId, ViewerPageFraction> = emptyMap()) : ViewerSeries

    data class Watching(
        val startPositionsMs: Map<PostId, Long> = emptyMap(),
        val seriesTitle: String? = null,
    ) : ViewerSeries
}

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
            media.fallbackUrls.any { location -> location.isNotBlank() } ||
            media.streams.any { stream -> stream.url.isNotBlank() }
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

fun newViewerPosts(current: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return emptyList()
    val seen = current.mapTo(mutableSetOf()) { post -> "${post.id.source.id}:${post.id.sourcePostId}" }
    return incoming.filter { post -> seen.add("${post.id.source.id}:${post.id.sourcePostId}") }
}
