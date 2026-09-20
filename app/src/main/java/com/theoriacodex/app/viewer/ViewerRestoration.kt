package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/** Exact bounded launch order captures sorting/filtering without rerunning a changing feed. */
internal data class ViewerRestorationRequest(
    val session: ViewerSessionIdentity,
    val selectedPostId: PostId?,
    val orderedPostIds: List<PostId>,
    val selectedMediaIndex: Int,
    val recentsSection: RecentPostSection?,
)

internal fun <T> viewerRestorationWindow(items: List<T>, selectedIndex: Int): List<T> {
    val start = (selectedIndex - VIEWER_RESTORATION_POST_LIMIT / 2)
        .coerceAtMost((items.size - VIEWER_RESTORATION_POST_LIMIT).coerceAtLeast(0))
        .coerceAtLeast(0)
    return items.subList(start, (start + VIEWER_RESTORATION_POST_LIMIT).coerceAtMost(items.size))
}

internal fun encodeViewerPostId(postId: PostId): String = "${postId.source.name}:${postId.sourcePostId}"

internal fun decodeViewerPostId(encoded: String?): PostId? {
    val pieces = encoded?.split(':', limit = 2) ?: return null
    if (pieces.size != 2 || pieces[1].isBlank()) return null
    val source = SourceKey.entries.firstOrNull { it.name == pieces[0] } ?: return null
    return PostId(source, pieces[1])
}

private const val VIEWER_RESTORATION_POST_LIMIT = 64
