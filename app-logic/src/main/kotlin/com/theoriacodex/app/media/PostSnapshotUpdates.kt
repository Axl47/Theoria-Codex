package com.theoriacodex.app.media

import com.theoriacodex.domain.model.keepingListingDataOf
import com.theoriacodex.domain.model.Post

/**
 * Resolving media updates one existing card without admitting posts or changing feed order. The card keeps
 * the listing's comment hint, which a detail response seldom repeats.
 */
fun List<Post>.replacingPostSnapshot(post: Post): List<Post> {
    val index = indexOfFirst { it.id == post.id }
    if (index < 0) return this
    return toMutableList().apply { this[index] = post.keepingListingDataOf(this[index]) }
}
