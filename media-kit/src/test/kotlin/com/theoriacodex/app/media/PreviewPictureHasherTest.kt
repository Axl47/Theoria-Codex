package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPictureHasherTest {
    @Test fun `a listing waits for its previews only as long as the budget, and the next listing has the late hashes (ADR 0006)`() = runTest {
        val loads = mutableListOf<String>()
        val hasher = PreviewPictureHasher(backgroundScope) { candidate, _ ->
            loads += candidate.url
            when {
                "slow" in candidate.url -> {
                    delay(150)
                    PictureHashResult.Hashed(7)
                }
                "flat" in candidate.url -> PictureHashResult.Unhashable
                else -> PictureHashResult.Hashed(candidate.url.length.toLong())
            }
        }
        val posts = listOf(post("quick"), post("slow"), post("flat"), post("video", mime = "video/mp4"))

        val annotated = hasher.annotate(posts, budgetMs = 100)

        assertEquals(listOf(posts[0].preview.url!!.length.toLong(), null, null, null), annotated.map { it.pictureHash })
        assertEquals(100L, currentTime)
        advanceTimeBy(50)
        runCurrent()

        assertEquals(listOf(posts[0].preview.url!!.length.toLong(), 7L, null, null), hasher.annotate(posts).map { it.pictureHash })
        assertEquals(3, loads.size)
    }

    @Test fun `a preview that waited for a loader slot while its host was rate limited is read from cache only`() = runTest {
        var limited = false
        val gate = CompletableDeferred<Unit>()
        val cachedOnly = mutableMapOf<String, Boolean>()
        val hasher = PreviewPictureHasher(backgroundScope, limited = { limited }, concurrentLoads = 1) { candidate, fromCache ->
            cachedOnly[candidate.url] = fromCache
            if ("first" in candidate.url) gate.await()
            PictureHashResult.Unavailable
        }
        val first = async { hasher.hash(post("first")) }
        val second = async { hasher.hash(post("second")) }
        runCurrent()
        limited = true
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(mapOf(post("first").preview.url to false, post("second").preview.url to true), cachedOnly)
    }

    private fun post(name: String, mime: String = "image/jpeg") = Post(
        id = PostId(SourceKey.DANBOORU, name), preview = ImageRef("https://cdn.example/$name.jpg", null, mime), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(), authorName = null,
        createdAtEpochMs = null,
    )
}
