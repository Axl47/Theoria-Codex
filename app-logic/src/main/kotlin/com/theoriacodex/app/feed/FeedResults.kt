package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Post

/** Keeps accepted order while admitting new canonical IDs and fresher payloads for existing IDs. */
fun mergeFeedPosts(existing: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap(existing.associateBy(Post::id))
    incoming.forEach { post -> byId[post.id] = post }
    return byId.values.toList()
}
