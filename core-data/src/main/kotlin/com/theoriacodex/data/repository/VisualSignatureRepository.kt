package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.flow.Flow

/**
 * What the app derived from stored posts' pictures: a perceptual hash (see [Post.pictureHash]), so a copy of a liked or
 * saved picture on another site is recognised, and what the on-device tagger saw ([PictureVision]). Rows exist only
 * while their post is stored; they are derived data and never backed up.
 */
interface VisualSignatureRepository {
    /** Every stored post's picture hash, again whenever one is added. */
    fun observePictureHashes(): Flow<Map<PostId, Long>>

    /**
     * Records [hash] for [postId], or that its picture cannot be hashed (null) so it is not tried again. Keeps what the
     * tagger stored. Ignored when the post is not stored.
     */
    suspend fun put(postId: PostId, hash: Long?)

    /** Up to [limit] liked or saved posts whose picture has not been hashed or tried yet, most recently stored first. */
    suspend fun libraryPostsWithoutSignature(limit: Int): List<Post>

    /**
     * Stores [entries] in one transaction; entries for posts that are not stored are skipped. A post with no row yet
     * still has its picture hash to try ([libraryPostsWithoutSignature]); an existing hash is kept.
     */
    suspend fun putVision(entries: List<PictureVision>)

    /** Up to [limit] liked or saved posts that [modelId] has not analysed (including those another model did), most recently stored first. */
    suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post>

    /**
     * Up to [limit] liked or saved posts whose [modelId] analysis awaits their frames ([VisionFrames.WANTED]) and has
     * failed fewer than [MAX_FRAME_ATTEMPTS] reads, most recently stored first, after the first [skip].
     */
    suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int = 0): List<Post>

    /**
     * Records a failed read of [postId]'s frames against its [modelId] analysis, which keeps standing. A failure that
     * will repeat ([permanently]) sets the frames aside for good; others are tried again until [MAX_FRAME_ATTEMPTS].
     */
    suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean)

    /** Marks [postIds]' [modelId] analyses as reading all their posts offer ([VisionFrames.NONE]), in one transaction: they do not play. */
    suspend fun unwantFrames(postIds: List<PostId>, modelId: String)

    /** The predicted tags of every stored post [modelId] analysed with tags, again whenever one changes. */
    fun observePredictedTags(modelId: String): Flow<Map<PostId, List<PredictedTag>>>

    /** How far [modelId] has come through the liked or saved posts, again whenever it changes. */
    fun observeVisionProgress(modelId: String): Flow<VisionProgress>

    /** Forgets everything the tagger stored, for any model (its files were deleted). Picture hashes stay. */
    suspend fun clearVision()
}

/**
 * What one tagger model saw in a stored post's picture, and from what ([frames]). A null [embedding] and null [tags]
 * mean it could not be analysed, so it is not tried again. The embedding is opaque bytes here; nothing reads it yet. It
 * is stored now so the swipe-trained taste scorer and "Similar in your library" can use the Library without tagging it
 * again.
 */
data class PictureVision(
    val postId: PostId,
    val modelId: String,
    val embedding: ByteArray?,
    val tags: List<PredictedTag>?,
    val frames: VisionFrames = VisionFrames.NONE,
) {
    override fun equals(other: Any?): Boolean = other is PictureVision && postId == other.postId && modelId == other.modelId &&
        tags == other.tags && frames == other.frames && (embedding contentEquals other.embedding)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * postId.hashCode() + modelId.hashCode()) + (embedding?.contentHashCode() ?: 0)) + (tags?.hashCode() ?: 0)) +
            frames.hashCode()
}

/** What a [PictureVision] read of a post: its still alone, its still while its frames wait, or its still and frames. Stored by position: keep the order. */
enum class VisionFrames { NONE, WANTED, READ }

/** Failed frame reads after which a post's still analysis stands for good. */
const val MAX_FRAME_ATTEMPTS = 3

/** [analysed] of the [total] liked or saved posts have been through the model, frames included where they play. */
data class VisionProgress(val analysed: Int, val total: Int)
