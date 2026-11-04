package com.theoriacodex.app.media

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

data class DurationFilterReadiness(
    val pendingCount: Int,
    val candidateCount: Int,
) {
    val isResolving: Boolean
        get() = pendingCount > 0

    /** The next page may load while a few lookups remain, so scrolling meets posts instead of a wait. */
    val blocksPaging: Boolean
        get() = pendingCount > DURATION_PAGING_ALLOWANCE
}

private const val DURATION_PAGING_ALLOWANCE = 8

fun durationFilterReadiness(
    posts: List<Post>,
    durationFilterActive: Boolean,
    stateByPostId: Map<PostId, MediaDurationState>,
): DurationFilterReadiness {
    if (!durationFilterActive) return DurationFilterReadiness(0, 0)
    val candidates = posts.asSequence()
        .filter(::isAnimatedPost)
        .filter { post -> animatedDurationMs(post) == null }
        .distinctBy(Post::id)
        .toList()
    val pending = candidates.count { post ->
        stateByPostId[post.id].let { state -> state == null || state == MediaDurationState.Pending }
    }
    return DurationFilterReadiness(pendingCount = pending, candidateCount = candidates.size)
}
