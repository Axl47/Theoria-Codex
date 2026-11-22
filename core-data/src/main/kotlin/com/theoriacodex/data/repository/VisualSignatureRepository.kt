package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow

/**
 * Perceptual hashes of stored posts' pictures (see [Post.pictureHash]), so a copy of a liked or saved picture on another
 * site is recognised. Rows exist only while their post is stored; they are derived data and never backed up.
 */
interface VisualSignatureRepository {
    /** Every stored post's picture hash, again whenever one is added. */
    fun observePictureHashes(): Flow<Map<PostId, Long>>

    /**
     * Records [hash] for [postId], or that its picture cannot be hashed (null) so it is not tried again. Ignored when
     * the post is not stored.
     */
    suspend fun put(postId: PostId, hash: Long?)

    /** Up to [limit] liked or saved posts that have no record yet, most recently stored first. */
    suspend fun libraryPostsWithoutSignature(limit: Int): List<Post>
}
