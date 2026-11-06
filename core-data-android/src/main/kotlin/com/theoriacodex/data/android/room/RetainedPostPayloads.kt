package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.Post
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Keeps the Posts published by the previous projection so unchanged payloads are not decoded again.
 * Only the latest projection is retained; callers that share one instance must project the same rows.
 */
internal class RetainedPostPayloads(private val decode: (PostEntity) -> Post) {
    private var previous = emptyMap<Pair<String, String>, Snapshot>()
    private val digest = MessageDigest.getInstance("SHA-256")

    /** Number of Posts this projection currently keeps alive. */
    val size: Int
        @Synchronized get() = previous.size

    @Synchronized
    fun project(rows: List<PostEntity>): List<Post> {
        val next = HashMap<Pair<String, String>, Snapshot>(rows.size)
        val posts = rows.map { row ->
            val key = row.source to row.sourcePostId
            val fingerprint = digest.digest(row.payloadJson.toByteArray(Charsets.UTF_8))
            val retained = previous[key]?.takeIf { it.fingerprint.contentEquals(fingerprint) }
            val snapshot = retained ?: Snapshot(fingerprint, decode(row))
            next[key] = snapshot
            snapshot.post
        }
        previous = next
        return posts
    }

    private class Snapshot(val fingerprint: ByteArray, val post: Post)
}

/**
 * Small LRU of projections that outlives one collection, so a screen returning to a recent Codex
 * reuses its decoded Posts after retained flows stopped. A projection larger than [maxRetainedPosts]
 * is dropped once its collection ends, so large libraries do not become a process-wide cache;
 * an active collector keeps its own reference either way.
 */
internal class RetainedPostPayloadCache(
    private val decode: (PostEntity) -> Post,
    private val maxEntries: Int = 8,
    private val maxRetainedPosts: Int = 5_000,
) {
    private val entries = LinkedHashMap<String, RetainedPostPayloads>(16, 0.75f, true)

    @Synchronized
    fun projection(key: String): RetainedPostPayloads = entries.getOrPut(key) { RetainedPostPayloads(decode) }

    /** Drops projections that alone exceed the budget, then least recently used ones until both bounds hold. */
    @Synchronized
    fun trim() {
        entries.values.removeAll { it.size > maxRetainedPosts }
        val iterator = entries.values.iterator()
        var retained = entries.values.sumOf(RetainedPostPayloads::size)
        while (iterator.hasNext() && (entries.size > maxEntries || retained > maxRetainedPosts)) {
            retained -= iterator.next().size
            iterator.remove()
        }
    }
}

/** Only identities, small fingerprints and the already-published Posts survive an emission. */
internal fun Flow<List<PostEntity>>.decodePostSnapshots(
    payloads: RetainedPostPayloadCache,
    key: String,
): Flow<List<Post>> = flow {
    val projection = payloads.projection(key)
    collect { rows ->
        val posts = projection.project(rows)
        payloads.trim()
        emit(posts)
    }
}.distinctUntilChanged().flowOn(Dispatchers.Default)
