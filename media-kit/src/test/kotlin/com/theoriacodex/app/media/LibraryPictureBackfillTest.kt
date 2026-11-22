package com.theoriacodex.app.media

import com.theoriacodex.data.repository.VisualSignatureRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPictureBackfillTest {
    @Test fun `the Library is hashed only while charging, and a preview that fails is skipped`() = runTest {
        val library = FakeSignatures(listOf(post("hashed"), post("flat"), post("offline")))
        val charging = MutableStateFlow(false)
        val hashed = mutableListOf<String>()
        val backfill = LibraryPictureBackfill(library, { post ->
            hashed += post.id.sourcePostId
            when (post.id.sourcePostId) {
                "hashed" -> PictureHashResult.Hashed(94)
                "flat" -> PictureHashResult.Unhashable
                else -> PictureHashResult.Unavailable
            }
        }, charging)
        backgroundScope.launch { backfill.run() }
        runCurrent()
        assertEquals(emptyList<String>(), hashed)

        charging.value = true
        runCurrent()

        assertEquals(mapOf(id("hashed") to 94L, id("flat") to null), library.records)
        assertEquals(listOf("hashed", "flat", "offline"), hashed)
    }

    private class FakeSignatures(private val library: List<Post>) : VisualSignatureRepository {
        val records = LinkedHashMap<PostId, Long?>()
        override fun observePictureHashes(): Flow<Map<PostId, Long>> = emptyFlow()
        override suspend fun put(postId: PostId, hash: Long?) {
            records[postId] = hash
        }
        override suspend fun libraryPostsWithoutSignature(limit: Int): List<Post> = library.filterNot { it.id in records }.take(limit)
    }

    private fun id(value: String) = PostId(SourceKey.DANBOORU, value)

    private fun post(value: String) = Post(
        id = id(value), preview = ImageRef("https://cdn.example/$value.jpg", null, "image/jpeg"), full = null, pageUrl = null,
        width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )
}
