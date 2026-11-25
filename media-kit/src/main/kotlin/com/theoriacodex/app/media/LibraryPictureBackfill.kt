package com.theoriacodex.app.media

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

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
            val hashes = HashMap<PostId, Long?>()
            val failed = ArrayList<PostId>()
            batch.mapConcurrent(concurrency = LOADS) { post -> post to if (reachable(post)) hash(post) else null }.forEach { (post, result) ->
                if (result == null) held += post.id else tried += post.id
                when (result) {
                    is PictureHashResult.Hashed -> hashes[post.id] = result.hash
                    PictureHashResult.Unhashable -> hashes[post.id] = null
                    PictureHashResult.Unavailable -> failed += post.id
                    null -> Unit
                }
            }
            signatures.putHashes(hashes)
            signatures.failHashes(failed)
        }
    }

    private companion object {
        const val BATCH = 32
        const val LOADS = 16
    }
}
