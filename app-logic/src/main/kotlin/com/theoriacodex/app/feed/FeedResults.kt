package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.distinctByFile
import com.theoriacodex.domain.orchestration.SourceRunStatus

/**
 * Keeps accepted order while admitting new canonical IDs and fresher payloads for existing IDs. A new ID whose file
 * the feed already shows from another site is left out, so the first copy keeps its place.
 */
fun mergeFeedPosts(existing: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap(existing.associateBy(Post::id))
    incoming.forEach { post -> byId[post.id] = post }
    return byId.values.distinctByFile()
}

fun mergeFeedStatuses(previous: List<SourceRunStatus>, incoming: List<SourceRunStatus>): List<SourceRunStatus> =
    (previous.associateBy(SourceRunStatus::source) + incoming.associateBy(SourceRunStatus::source))
        .values.sortedBy { it.source.id }
