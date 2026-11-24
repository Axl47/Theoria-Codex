package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.flow.Flow

@RequiresOptIn(message = "Only PictureTagger writes vision results, under its commits lock (docs/adr/0097-picture-tagger-session-and-commits.md)")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class VisionCommit

interface VisualSignatureRepository {
    fun observePictureHashes(): Flow<Map<PostId, Long>>

    suspend fun putHashes(hashes: Map<PostId, Long?>)

    suspend fun libraryPostsWithoutSignature(limit: Int, skip: Int = 0): List<Post>

    @VisionCommit
    suspend fun putVision(entries: List<PictureVision>)

    suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post>

    suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int = 0): List<Post>

    @VisionCommit
    suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean)

    @VisionCommit
    suspend fun unwantFrames(postIds: List<PostId>, modelId: String)

    fun observeVisionChanges(): Flow<Unit>

    suspend fun predictedTags(modelId: String): Map<PostId, List<PredictedTag>>

    fun observeVisionProgress(modelId: String): Flow<VisionProgress>

    fun observeLibraryTotal(): Flow<Int>

    @VisionCommit
    suspend fun clearVision()
}

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

enum class VisionFrames { NONE, WANTED, READ }

const val MAX_FRAME_ATTEMPTS = 3

data class VisionProgress(val analysed: Int, val total: Int)
