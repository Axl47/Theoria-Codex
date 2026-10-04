package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus

/** Keeps accepted order while admitting new canonical IDs and fresher payloads for existing IDs. */
fun mergeFeedPosts(existing: List<Post>, incoming: List<Post>): List<Post> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap(existing.associateBy(Post::id))
    incoming.forEach { post -> byId[post.id] = post }
    return byId.values.toList()
}

/** Map membership admits a request; a null value retries the first page after a root failure. */
fun settleFeedContinuations(
    previous: Map<SourceKey, String?>,
    attempted: Set<SourceKey>,
    returned: Map<SourceKey, String?>,
    statuses: List<SourceRunStatus>,
): Map<SourceKey, String?> = previous.toMutableMap().apply {
    val bySource = statuses.associateBy(SourceRunStatus::source)
    attempted.forEach { source ->
        when (bySource[source]?.state) {
            SourceRunState.SUCCESS -> {
                val token = returned[source]?.takeIf(String::isNotBlank)
                if (token == null) remove(source) else put(source, token)
            }
            SourceRunState.EXCLUDED -> remove(source)
            SourceRunState.FAILED, null -> Unit
        }
    }
}

fun mergeFeedStatuses(previous: List<SourceRunStatus>, incoming: List<SourceRunStatus>): List<SourceRunStatus> =
    (previous.associateBy(SourceRunStatus::source) + incoming.associateBy(SourceRunStatus::source))
        .values.sortedBy { it.source.name }
