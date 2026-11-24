package com.theoriacodex.app.media

import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPictureBackfillTest {
    @Test fun `the Library is hashed until done, and a preview that fails is skipped (ADR 0111)`() = runTest {
        val library = FakeSignatures(listOf(post("hashed"), post("flat"), post("offline")))
        val hashed = mutableListOf<String>()
        val backfill = LibraryPictureBackfill(library) { post ->
            hashed += post.id.sourcePostId
            when (post.id.sourcePostId) {
                "hashed" -> PictureHashResult.Hashed(94)
                "flat" -> PictureHashResult.Unhashable
                else -> PictureHashResult.Unavailable
            }
        }
        backfill.drain()

        assertEquals(mapOf(id("hashed") to 94L, id("flat") to null), library.records)
        assertEquals(listOf("hashed", "flat", "offline"), hashed)
        assertEquals(listOf(2), library.writes)
    }

    @Test fun `a Library that cannot be reached is read once, a batch at a time (ADR 0111)`() = runTest {
        val library = FakeSignatures(List(100) { post("offline-$it") })
        val asked = mutableListOf<String>()

        LibraryPictureBackfill(library) { post -> PictureHashResult.Unavailable.also { asked += post.id.sourcePostId } }.drain()

        assertEquals(List(100) { "offline-$it" }, asked)
        assertEquals(listOf(32, 32, 32, 4, 0), library.listed)
    }

    private class FakeSignatures(private val library: List<Post>) : VisualSignatureRepository {
        val records = LinkedHashMap<PostId, Long?>()
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = emptyFlow()
        val writes = mutableListOf<Int>()
        override suspend fun putHashes(hashes: Map<PostId, Long?>) {
            writes += hashes.size
            records += hashes
        }
        val listed = mutableListOf<Int>()
        override suspend fun libraryPostsWithoutSignature(limit: Int, skip: Int): List<Post> =
            library.filterNot { it.id in records }.drop(skip).take(limit).also { listed += it.size }
        override suspend fun putVision(entries: List<PictureVision>) = Unit
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int): List<Post> = emptyList()
        override suspend fun libraryPostsAwaitingFrames(modelId: String, limit: Int, skip: Int): List<Post> = emptyList()
        override suspend fun failFrames(postId: PostId, modelId: String, permanently: Boolean) = Unit
        override suspend fun unwantFrames(postIds: List<PostId>, modelId: String) = Unit
        override fun observeVisionChanges(): Flow<Unit> = emptyFlow()
        override suspend fun predictedTags(modelId: String): Map<PostId, List<PredictedTag>> = emptyMap()
        override fun observeVisionProgress(modelId: String): Flow<VisionProgress> = emptyFlow()
        override fun observeLibraryTotal(): Flow<Int> = emptyFlow()
        override suspend fun clearVision() = Unit
    }

    private fun id(value: String) = PostId(SourceKey.DANBOORU, value)

    private fun post(value: String) = Post(
        id = id(value), preview = ImageRef("https://cdn.example/$value.jpg", null, "image/jpeg"), full = null, pageUrl = null,
        width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
