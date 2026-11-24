package com.theoriacodex.app.media

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LibraryPictureBackfill(
    private val signatures: VisualSignatureRepository,
    private val reachable: (Post) -> Boolean = { true },
    private val hash: suspend (Post) -> PictureHashResult,
) {
    suspend fun drain() {
        val held = HashSet<PostId>()
        val tried = HashSet<PostId>()
        while (true) {
            val batch = signatures.libraryPostsWithoutSignature(BATCH, skip = held.size).filterNot { it.id in held }
            if (batch.isEmpty() || batch.any { it.id in tried }) return
            val (ready, unreachable) = batch.partition(reachable)
            unreachable.mapTo(held) { it.id }
            ready.mapTo(tried) { it.id }
            val hashes = HashMap<PostId, Long?>()
            val failed = ArrayList<PostId>()
            coroutineScope { ready.map { post -> async { post to hash(post) } }.awaitAll() }.forEach { (post, result) ->
                when (result) {
                    is PictureHashResult.Hashed -> hashes[post.id] = result.hash
                    PictureHashResult.Unhashable -> hashes[post.id] = null
                    PictureHashResult.Unavailable -> failed += post.id
                }
            }
            signatures.putHashes(hashes)
            signatures.failHashes(failed)
        }
    }

    private companion object {
        const val BATCH = 32
    }
}
