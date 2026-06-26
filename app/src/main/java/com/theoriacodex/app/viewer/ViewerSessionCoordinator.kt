package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

internal data class ViewerSession(
    val posts: List<Post>,
    val context: ViewerLaunchContext,
    val liveSearchBinding: Boolean = false,
    val searchVisibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
)

private val LAZY_MEDIA_RESOLUTION_SOURCES = setOf(
    SourceKey.NHENTAI,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
    SourceKey.IWARA,
)

private val REFRESHABLE_REMOTE_VIDEO_SOURCES = setOf(
    SourceKey.AIBOORU,
    SourceKey.GELBOORU,
    SourceKey.IWARA,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
)

internal fun requiresViewerPostResolution(post: Post, streamSource: ViewerStreamSource): Boolean {
    if (
        streamSource == ViewerStreamSource.CODEX ||
        streamSource == ViewerStreamSource.RECENTS
    ) {
        return post.id.source in REFRESHABLE_REMOTE_VIDEO_SOURCES &&
            (hasRemotePrimaryMedia(post) || requiresLazyMediaResolution(post))
    }
    return requiresLazyMediaResolution(post)
}

internal fun requiresLazyMediaResolution(post: Post): Boolean {
    val mediaRefs = buildList {
        addAll(post.media)
        post.full?.let { add(it) }
    }
    if (
        post.id.source in REFRESHABLE_REMOTE_VIDEO_SOURCES &&
        mediaRefs.any { ref -> isVideoMediaRef(ref) && ref.localPath.isNullOrBlank() && !ref.url.isNullOrBlank() }
    ) {
        return true
    }
    if (post.id.source !in LAZY_MEDIA_RESOLUTION_SOURCES) return false
    return mediaRefs.none { ref ->
        !ref.url.isNullOrBlank()
    }
}

private fun hasRemotePrimaryMedia(post: Post): Boolean {
    return buildList {
        addAll(post.media)
        post.full?.let { add(it) }
    }.any { ref ->
        !ref.url.isNullOrBlank() && ref.localPath.isNullOrBlank()
    }
}

internal fun mergeViewerPosts(current: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    val seen = current
        .mapTo(mutableSetOf()) { post -> "${post.id.source.name}:${post.id.sourcePostId}" }
    val merged = current.toMutableList()
    incoming.forEach { post ->
        val key = "${post.id.source.name}:${post.id.sourcePostId}"
        if (seen.add(key)) {
            merged += post
        }
    }
    return merged
}
