package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPictureHasherTest {
    @Test fun `a listing waits for its previews only as long as the budget, and late hashes still arrive`() = runTest {
        val slow = CompletableDeferred<PictureHashResult>()
        val loads = mutableListOf<String>()
        val late = mutableListOf<Post>()
        val hasher = PreviewPictureHasher(backgroundScope, { candidate ->
            loads += candidate.url
            when {
                "slow" in candidate.url -> slow.await()
                "flat" in candidate.url -> PictureHashResult.Unhashable
                else -> PictureHashResult.Hashed(candidate.url.length.toLong())
            }
        }, onLateHash = late::add)
        val posts = listOf(post("quick"), post("slow"), post("flat"), post("video", mime = "video/mp4"))

        val annotated = hasher.annotate(posts, budgetMs = 100)

        assertEquals(listOf(posts[0].preview.url!!.length.toLong(), null, null, null), annotated.map { it.pictureHash })
        slow.complete(PictureHashResult.Hashed(7))
        runCurrent()
        assertEquals(listOf(posts[1].copy(pictureHash = 7)), late)

        // Answers are remembered by address, so the next listing of these posts loads nothing.
        assertEquals(listOf(posts[0].preview.url!!.length.toLong(), 7L, null, null), hasher.annotate(posts).map { it.pictureHash })
        assertEquals(3, loads.size)
    }

    private fun post(name: String, mime: String = "image/jpeg") = Post(
        id = PostId(SourceKey.DANBOORU, name), preview = ImageRef("https://cdn.example/$name.jpg", null, mime), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null,
        createdAtEpochMs = null,
    )
}
