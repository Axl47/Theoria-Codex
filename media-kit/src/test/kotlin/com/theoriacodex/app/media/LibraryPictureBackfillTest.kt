package com.theoriacodex.app.media

import com.theoriacodex.data.repository.PREVIEW_RETRY_MS
import com.theoriacodex.data.repository.PictureVision
import com.theoriacodex.data.repository.VisionProgress
import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPictureBackfillTest {
    @Test fun `the Library is hashed until done, and a preview that fails is skipped`() = runTest {
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
        assertEquals(setOf(id("offline")), library.failedAt.keys)
    }

    @Test fun `a preview that failed is not asked for again until a day has passed`() = runTest {
        val library = FakeSignatures(listOf(post("offline")))
        var asked = 0
        val backfill = LibraryPictureBackfill(library) { asked++; PictureHashResult.Unavailable }

        backfill.drain()
        library.now = PREVIEW_RETRY_MS - 1
        backfill.drain()
        assertEquals(1, asked)
        library.now = PREVIEW_RETRY_MS
        backfill.drain()
        assertEquals(2, asked)
    }

    @Test fun `posts on a host that is holding requests off are left for a later run without a request`() = runTest {
        val library = FakeSignatures(listOf(post("held-1"), post("ok"), post("held-2")))
        val asked = mutableListOf<String>()
        LibraryPictureBackfill(library, { post -> !post.id.sourcePostId.startsWith("held") }) { post ->
            PictureHashResult.Hashed(1).also { asked += post.id.sourcePostId }
        }.drain()

        assertEquals(listOf("ok"), asked)
        assertEquals(setOf(id("ok")), library.records.keys)
        assertTrue(library.failedAt.isEmpty())
    }

    @Test fun `ADR 0111 a Library that cannot be reached is read once, a batch at a time`() = runTest {
        val library = FakeSignatures(List(100) { post("offline-$it") })
        val asked = mutableListOf<String>()

        LibraryPictureBackfill(library) { post -> PictureHashResult.Unavailable.also { asked += post.id.sourcePostId } }.drain()

        assertEquals(List(100) { "offline-$it" }, asked)
        assertEquals(listOf(32, 32, 32, 4, 0), library.listed)
    }

    @Test fun `no preview load starts once its host is holding requests off`() = runTest {
        val library = FakeSignatures(List(20) { post("p$it") })
        var limited = false
        var loadedWhileLimited = 0
        LibraryPictureBackfill(library, { !limited }) {
            if (limited) loadedWhileLimited++
            limited = true
            delay(10)
            PictureHashResult.Hashed(1)
        }.drain()

        assertEquals(0, loadedWhileLimited)
        assertTrue(library.records.size < 20)
        assertTrue(library.failedAt.isEmpty())
    }

    private class FakeSignatures(private val library: List<Post>) : VisualSignatureRepository {
        val records = LinkedHashMap<PostId, Long?>()
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = emptyFlow()
        val writes = mutableListOf<Int>()
        override suspend fun putHashes(hashes: Map<PostId, Long?>) {
            writes += hashes.size
            records += hashes
        }
        var now = 0L
        val failedAt = LinkedHashMap<PostId, Long>()
        override suspend fun failPreviews(postIds: Collection<PostId>) {
            postIds.forEach { failedAt[it] = now }
        }
        val listed = mutableListOf<Int>()
        override suspend fun libraryPostsWithoutSignature(limit: Int, skip: Int): List<Post> =
            library.filterNot { it.id in records || (failedAt[it.id] ?: Long.MIN_VALUE) > now - PREVIEW_RETRY_MS }
                .drop(skip).take(limit).also { listed += it.size }
        override suspend fun putVision(entries: List<PictureVision>) = Unit
        override suspend fun libraryPostsWithoutVision(modelId: String, limit: Int, skip: Int): List<Post> = emptyList()
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
