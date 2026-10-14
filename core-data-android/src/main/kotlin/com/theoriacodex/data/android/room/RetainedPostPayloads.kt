package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.Post
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Reuse is scoped to one active observation, not a process cache that thrashes on large scans. */
internal class RetainedPostPayloads(private val decode: (PostEntity) -> Post) {
    private var previous = emptyMap<Pair<String, String>, Snapshot>()
    private val digest = MessageDigest.getInstance("SHA-256")

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

/** Only identities, small fingerprints and the already-published Posts survive an emission. */
internal fun Flow<List<PostEntity>>.decodePostSnapshots(codec: LocalPostPayloadCodec): Flow<List<Post>> = flow {
    val projection = RetainedPostPayloads(codec::decode)
    collect { rows -> emit(projection.project(rows)) }
}.distinctUntilChanged().flowOn(Dispatchers.Default)
