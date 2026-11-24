package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.Post
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class RetainedPostPayloads(private val decode: (PostEntity) -> Post) {
    private var previous = emptyMap<Pair<String, String>, Snapshot>()
    private val digest = MessageDigest.getInstance("SHA-256")

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

internal class RetainedPostPayloadCache(
    private val decode: (PostEntity) -> Post,
    private val maxEntries: Int = 8,
    private val maxRetainedPosts: Int = 5_000,
) {
    private val entries = LinkedHashMap<String, RetainedPostPayloads>(16, 0.75f, true)

    @Synchronized
    fun projection(key: String): RetainedPostPayloads = entries.getOrPut(key) { RetainedPostPayloads(decode) }

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
