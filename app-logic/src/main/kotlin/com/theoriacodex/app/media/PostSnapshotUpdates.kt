package com.theoriacodex.app.media

import com.theoriacodex.domain.model.Post

/** Resolving media updates one existing card without admitting posts or changing feed order. */
fun List<Post>.replacingPostSnapshot(post: Post): List<Post> {
    val index = indexOfFirst { it.id == post.id }
    if (index < 0) return this
    return toMutableList().apply { this[index] = post }
}
