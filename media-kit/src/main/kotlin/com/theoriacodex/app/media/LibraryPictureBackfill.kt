package com.theoriacodex.app.media

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LibraryPictureBackfill(
    private val signatures: VisualSignatureRepository,
    private val hash: suspend (Post) -> PictureHashResult,
) {
    suspend fun drain() {
        val unavailable = HashSet<PostId>()
        while (true) {
            val batch = signatures.libraryPostsWithoutSignature(BATCH, skip = unavailable.size).filterNot { it.id in unavailable }
            if (batch.isEmpty()) return
            val hashes = HashMap<PostId, Long?>()
            coroutineScope { batch.map { post -> async { post to hash(post) } }.awaitAll() }.forEach { (post, result) ->
                when (result) {
                    is PictureHashResult.Hashed -> hashes[post.id] = result.hash
                    PictureHashResult.Unhashable -> hashes[post.id] = null
                    PictureHashResult.Unavailable -> unavailable += post.id
                }
            }
            signatures.putHashes(hashes)
        }
    }

    private companion object {
        const val BATCH = 32
    }
}
