package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimatedDurationEnrichmentPolicyTest {
    @Test
    fun `proactive request requires enabled resolution and an unknown animated post`() {
        val unknownAnimated = post(1)
        val knownAnimated = unknownAnimated.copy(durationMs = 2_000L)
        val static = unknownAnimated.copy(
            id = PostId(SourceKey.GELBOORU, "static"),
            full = ImageRef("https://example.test/static.jpg", null, "image/jpeg"),
            media = emptyList(),
        )

        assertTrue(shouldRequestAnimatedDurationEnrichment(listOf(unknownAnimated), true))
        assertFalse(shouldRequestAnimatedDurationEnrichment(listOf(unknownAnimated), false))
        assertFalse(shouldRequestAnimatedDurationEnrichment(listOf(knownAnimated, static), true))
    }

    @Test
    fun `candidate policy deduplicates excludes and bounds each batch`() {
        val posts = (1..10).map(::post) + post(1)
        val candidates = animatedDurationEnrichmentCandidates(
            posts = posts,
            excludedPostIds = setOf(post(1).id),
        )

        assertEquals((2..9).map { post(it).id }, candidates.map(Post::id))
    }

    @Test
    fun `lane drains past a negative first batch and coalesces appended posts`() = runTest {
        var identity = "seed"
        val posts = (1..8).map(::post).toMutableList()
        val firstProbe = CompletableDeferred<Unit>()
        val applied = mutableListOf<AnimatedDurationEnrichment>()
        val calls = mutableListOf<PostId>()
        val enricher = object : AnimatedDurationEnricher {
            override suspend fun enrich(post: Post): AnimatedDurationEnrichment? {
                calls += post.id
                if (post.id.sourcePostId == "1") firstProbe.await()
                return post.id.sourcePostId.toInt().takeIf { it > 8 }
                    ?.let { AnimatedDurationEnrichment(post.id, it * 1_000L) }
            }
        }
        val lane = AnimatedDurationEnrichmentLane(
            scope = this,
            enricher = enricher,
            currentIdentity = { identity },
            currentPosts = { posts.toList() },
            applyEnrichments = { _, values -> applied += values },
        )

        lane.request(identity)
        posts += post(9)
        lane.request(identity)
        firstProbe.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(post(9).id), applied.map(AnimatedDurationEnrichment::postId))
        assertEquals((1..9).map { post(it).id }, calls.sortedBy { it.sourcePostId.toInt() })
    }

    @Test
    fun `stale identity completion is never published`() = runTest {
        var identity = "old"
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val lane = AnimatedDurationEnrichmentLane(
            scope = this,
            enricher = object : AnimatedDurationEnricher {
                override suspend fun enrich(post: Post): AnimatedDurationEnrichment {
                    gate.await()
                    return AnimatedDurationEnrichment(post.id, 1_000L)
                }
            },
            currentIdentity = { identity },
            currentPosts = { listOf(post(1)) },
            applyEnrichments = { key, _ -> published += key },
        )

        lane.request("old")
        identity = "new"
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(published.isEmpty())
    }

    @Test
    fun `null outcome retries only after the bounded negative decision expires`() = runTest {
        var now = 1_000L
        var calls = 0
        val lane = AnimatedDurationEnrichmentLane(
            scope = this,
            enricher = object : AnimatedDurationEnricher {
                override suspend fun enrich(post: Post): AnimatedDurationEnrichment? {
                    calls += 1
                    return null
                }
            },
            currentIdentity = { "seed" },
            currentPosts = { listOf(post(1)) },
            applyEnrichments = { _, _ -> error("Null enrichment must not publish") },
            clock = { now },
            negativeRetryAfterMs = 500L,
        )

        lane.request("seed")
        advanceUntilIdle()
        lane.request("seed")
        advanceUntilIdle()
        assertEquals(1, calls)

        now += 500L
        lane.request("seed")
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    private fun post(index: Int): Post = Post(
        id = PostId(SourceKey.IWARA, index.toString()),
        preview = ImageRef("https://example.test/$index.jpg", null, "image/jpeg"),
        full = null,
        media = emptyList(),
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = null,
    )
}
