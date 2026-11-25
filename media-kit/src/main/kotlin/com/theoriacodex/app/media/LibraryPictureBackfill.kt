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
    suspend fun drain(): Boolean {
        val held = HashSet<PostId>()
        val tried = HashSet<PostId>()
        while (true) {
            val batch = signatures.libraryPostsWithoutSignature(BATCH, skip = held.size).filterNot { it.id in held }
            if (batch.isEmpty() || batch.any { it.id in tried }) return held.isNotEmpty()
            val results = load(batch)
            results.forEach { (post, result) -> if (result == null) held += post.id else tried += post.id }
            signatures.putHashes(results.mapNotNull { (post, result) -> result.storedHash()?.let { post.id to it.value } }.toMap())
            signatures.failPreviews(results.filter { it.second == PictureHashResult.Unavailable }.map { it.first.id })
        }
    }

    private suspend fun load(batch: List<Post>): List<Pair<Post, PictureHashResult?>> =
        batch.mapConcurrent(concurrency = LOADS) { post ->
            val loaded = if (reachable(post)) hash(post) else null
            post to loaded.takeUnless { it == PictureHashResult.Unavailable && !reachable(post) }
        }

    private fun PictureHashResult?.storedHash(): StoredHash? = when (this) {
        is PictureHashResult.Hashed -> StoredHash(hash)
        PictureHashResult.Unhashable -> StoredHash(null)
        PictureHashResult.Unavailable, null -> null
    }

    private class StoredHash(val value: Long?)

    private companion object {
        const val BATCH = 32
        const val LOADS = 16
    }
}
