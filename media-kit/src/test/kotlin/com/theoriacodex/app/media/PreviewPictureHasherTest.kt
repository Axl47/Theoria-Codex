package com.theoriacodex.app.media

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.size.Size
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreviewPictureHasherTest {
    @Test fun `a listing waits for its previews only as long as the budget, and the next listing has the late hashes (ADR 0006, 0111)`() = runTest {
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

    @Test fun `a listing waits 600 ms by default for its previews (ADR 0111)`() = runTest {
        val hasher = PreviewPictureHasher(backgroundScope) { candidate, _ ->
            delay(if ("inside" in candidate.url) 599 else 601)
            PictureHashResult.Hashed(3)
        }

        val annotated = hasher.annotate(listOf(post("inside"), post("outside")))

        assertEquals(listOf(3L, null), annotated.map { it.pictureHash })
        assertEquals(600L, currentTime)
    }

    @Test fun `a preview's answer is remembered by its address, shared while loading, and an unavailable one is tried again (ADR 0111)`() = runTest {
        val loads = mutableListOf<String>()
        val hasher = PreviewPictureHasher(backgroundScope) { candidate, _ ->
            val name = candidate.url.substringAfterLast('/')
            loads += name
            when (name) {
                "gone.jpg" -> PictureHashResult.Unavailable
                "broken.jpg" -> throw java.io.IOException("load failed")
                "flat.jpg" -> PictureHashResult.Unhashable
                "shared.jpg" -> {
                    delay(50)
                    PictureHashResult.Hashed(9)
                }
                else -> PictureHashResult.Hashed(5)
            }
        }
        val custom = post("custom").copy(id = PostId(requireNotNull(SourceKey.parse("custom_booru_example")), "custom"))
        val posts = listOf(post("seen"), post("flat"), post("gone"), post("broken"), custom)

        assertEquals(5L, hasher.annotate(posts).last().pictureHash)
        hasher.annotate(posts)
        val first = async { hasher.annotate(listOf(post("shared"))) }
        val second = async { hasher.annotate(listOf(post("shared"))) }
        assertEquals(listOf(9L), first.await().map { it.pictureHash })
        assertEquals(listOf(9L), second.await().map { it.pictureHash })

        assertEquals(
            mapOf("seen.jpg" to 1, "flat.jpg" to 1, "gone.jpg" to 2, "broken.jpg" to 2, "custom.jpg" to 1, "shared.jpg" to 1),
            loads.groupingBy { it }.eachCount(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `answers are keyed by preview address, not post, and a load that finishes at once is still remembered (ADR 0111)`() = runTest {
        val loads = mutableListOf<String>()
        val hasher = PreviewPictureHasher(CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { candidate, _ ->
            loads += candidate.url
            PictureHashResult.Hashed(4)
        }
        val original = post("shared")
        val repost = original.copy(id = PostId(SourceKey.GELBOORU, "repost"))
        val renamed = original.copy(preview = ImageRef("https://cdn.example/other.jpg", null, "image/jpeg"))

        assertEquals(listOf(4L), hasher.annotate(listOf(original)).map { it.pictureHash })
        assertEquals(listOf(4L, 4L), hasher.annotate(listOf(repost, renamed)).map { it.pictureHash })

        assertEquals(listOf("https://cdn.example/shared.jpg", "https://cdn.example/other.jpg"), loads)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `the still preview is a small first frame decoded off the main thread, outside the memory cache, from the card's address (ADR 0111)`() {
        val context = RuntimeEnvironment.getApplication()
        val requests = mutableListOf<Pair<ImageRequest, Size>>()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Coil.setImageLoader(
            ImageLoader.Builder(context).components {
                add(Interceptor { chain ->
                    synchronized(requests) { requests += chain.request to chain.size }
                    ErrorResult(null, chain.request, IllegalStateException("not loaded"))
                })
            }.build(),
        )
        try {
            val card = "https://video-cdn4.gelbooru.com/images/card.jpg"
            runBlocking {
                loadPreviewStill(context, requireNotNull(postPreviewImageCandidate(post("card").copy(id = PostId(SourceKey.GELBOORU, "card"), preview = ImageRef(card, null, "image/jpeg")))), 96)
                pictureHashAt(context, card, SourceKey.GELBOORU, cachedOnly = true)
            }

            assertEquals(listOf(CachePolicy.ENABLED, CachePolicy.DISABLED), requests.map { it.first.networkCachePolicy })
            requests.forEach { (request, size) ->
                assertEquals("https://gelbooru.com/images/card.jpg", request.data)
                assertEquals(Size(96, 96), size)
                assertTrue(request.decoderFactory is BitmapFactoryDecoder.Factory)
                assertEquals(CachePolicy.DISABLED, request.memoryCachePolicy)
                assertEquals("The still fills the disk cache the card reads", CachePolicy.ENABLED, request.diskCachePolicy)
                assertEquals(false, request.allowHardware)
                assertEquals(Dispatchers.Default, request.interceptorDispatcher)
            }
        } finally {
            Coil.reset()
            Dispatchers.resetMain()
        }
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
