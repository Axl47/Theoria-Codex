package com.theoriacodex.app.media

import com.theoriacodex.domain.model.keepingListingDataOf
import com.theoriacodex.domain.model.Post

fun List<Post>.replacingPostSnapshot(post: Post): List<Post> {
    val index = indexOfFirst { it.id == post.id }
    if (index < 0) return this
    return toMutableList().apply { this[index] = post.keepingListingDataOf(this[index]) }
}
