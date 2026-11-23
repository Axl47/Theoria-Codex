package com.theoriacodex.app.media

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Hashes the pictures of liked and saved posts that have no record yet ([VisualSignatureRepository]), so a repost of
 * an older Library post on another site is recognised too. A post saved from a listing already brought its hash; this
 * covers the Library from before and posts saved without one. The app's charging-only background job runs [drain].
 */
class LibraryPictureBackfill(
    private val signatures: VisualSignatureRepository,
    private val hash: suspend (Post) -> PictureHashResult,
) {
    /** Hashes a batch of previews at a time until none is left; a preview that fails to load is skipped this run. */
    suspend fun drain() {
        val unavailable = HashSet<PostId>()
        while (true) {
            val batch = signatures.libraryPostsWithoutSignature(unavailable.size + BATCH).filterNot { it.id in unavailable }
            if (batch.isEmpty()) return
            coroutineScope { batch.map { post -> async { post to hash(post) } }.awaitAll() }.forEach { (post, result) ->
                when (result) {
                    is PictureHashResult.Hashed -> signatures.put(post.id, result.hash)
                    PictureHashResult.Unhashable -> signatures.put(post.id, null)
                    PictureHashResult.Unavailable -> unavailable += post.id
                }
            }
        }
    }

    private companion object {
        const val BATCH = 32
    }
}
