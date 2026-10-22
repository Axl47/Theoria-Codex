package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId

/** Session-local timestamps for the current post and five neighboring posts in either direction. */
internal class ViewerVideoPositions {
    private var retainedPosts = emptySet<PostId>()
    private val positions = mutableMapOf<ViewerMediaKey, Long>()

    fun retainAround(posts: List<PostId>, currentIndex: Int) {
        retainedPosts = posts.slice(
            (currentIndex - 5).coerceAtLeast(0)..(currentIndex + 5).coerceAtMost(posts.lastIndex),
        ).toSet()
        positions.keys.retainAll { it.postId in retainedPosts }
    }

    fun position(key: ViewerMediaKey): Long = positions[key] ?: 0L

    fun save(key: ViewerMediaKey, positionMs: Long) {
        if (key.postId in retainedPosts) positions[key] = positionMs.coerceAtLeast(0L)
    }
}
