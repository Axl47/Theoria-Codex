package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimatedDurationEnrichmentServiceTest {
    @Test
    fun `same post shares one cross-route flight`() = runTest {
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val probeCalls = AtomicInteger()
        val service = service(
            hasProviderDurationResolver = { true },
            resolveProviderDuration = { post ->
                providerCalls.incrementAndGet()
                DurationMetadataSourceResult.AuthoritativeMedia(requireNotNull(post.full))
            },
            probeDuration = {
                probeCalls.incrementAndGet()
                probeStarted.complete(Unit)
                releaseProbe.await()
                2_400L
            },
        )

        val searchWaiter = async { service.enrich(animatedPost("shared")) }
        val creatorWaiter = async { service.enrich(animatedPost("shared")) }
        runCurrent()
        probeStarted.await()

        assertEquals(0, providerCalls.get())
        assertEquals(1, probeCalls.get())
        releaseProbe.complete(Unit)
        advanceUntilIdle()
        assertEquals(2_400L, searchWaiter.await()?.durationMs)
        assertEquals(searchWaiter.await(), creatorWaiter.await())
        service.close()
    }

    @Test
    fun `parallel probes stay within configured bound`() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val service = service(
            maxConcurrentWork = 2,
            probeDuration = {
                val count = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, count) }
                started.send(Unit)
                try {
                    release.receive()
                    1_000L
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val jobs = (1..4).map { index -> async { service.enrich(animatedPost("post-$index")) } }
        runCurrent()
        started.receive()
        started.receive()
        assertEquals(2, maximum.get())
        assertFalse(started.tryReceive().isSuccess)

        repeat(4) { release.trySend(Unit) }
        advanceUntilIdle()
        assertTrue(jobs.all { job -> job.await()?.durationMs == 1_000L })
        assertEquals(2, maximum.get())
        service.close()
    }

    @Test
    fun `default acquisition permits only one probe worker`() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val service = service(
            probeDuration = {
                val count = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, count) }
                started.send(Unit)
                try {
                    release.receive()
                    1_000L
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val first = async { service.enrich(animatedPost("default-one")) }
        val second = async { service.enrich(animatedPost("default-two")) }
        runCurrent()
        started.receive()
        assertFalse(started.tryReceive().isSuccess)

        release.send(Unit)
        runCurrent()
        started.receive()
        release.send(Unit)
        advanceUntilIdle()

        assertEquals(1_000L, first.await()?.durationMs)
        assertEquals(1_000L, second.await()?.durationMs)
        assertEquals(1, maximum.get())
        service.close()
    }

    @Test
    fun `success cache is reused and evicts least recently used post`() = runTest {
        val calls = mutableMapOf<String, Int>()
        val service = service(
            successCacheSize = 2,
            probeDuration = { post ->
                val id = post.id.sourcePostId
                calls[id] = calls.getOrDefault(id, 0) + 1
                id.last().digitToInt().toLong() * 1_000L
            },
        )

        assertEquals(1_000L, enrich(service, animatedPost("post-1"))?.durationMs)
        assertEquals(2_000L, enrich(service, animatedPost("post-2"))?.durationMs)
        assertEquals(1_000L, enrich(service, animatedPost("post-1"))?.durationMs)
        assertEquals(3_000L, enrich(service, animatedPost("post-3"))?.durationMs)
        assertEquals(2_000L, enrich(service, animatedPost("post-2"))?.durationMs)

        assertEquals(mapOf("post-1" to 1, "post-2" to 2, "post-3" to 1), calls)
        service.close()
    }

    @Test
    fun `negative decision suppresses hot retry then expires`() = runTest {
        var now = 1_000L
        var probes = 0
        val service = service(
            clock = { now },
            negativeTtlMs = 500L,
            probeDuration = {
                probes += 1
                if (probes == 1) null else 9_000L
            },
        )
        val post = animatedPost("retry")

        assertNull(enrich(service, post))
        assertNull(enrich(service, post))
        assertEquals(1, probes)
        now += 501L
        assertEquals(9_000L, enrich(service, post)?.durationMs)
        assertEquals(2, probes)
        service.close()
    }

    @Test
    fun `bounded negative cache permits evicted decisions to retry`() = runTest {
        val calls = mutableMapOf<String, Int>()
        val service = service(
            negativeCacheSize = 1,
            probeDuration = { post ->
                val id = post.id.sourcePostId
                calls[id] = calls.getOrDefault(id, 0) + 1
                null
            },
        )

        assertNull(enrich(service, animatedPost("first")))
        assertNull(enrich(service, animatedPost("second")))
        assertNull(enrich(service, animatedPost("first")))
        assertEquals(mapOf("first" to 2, "second" to 1), calls)
        service.close()
    }

    @Test
    fun `canceling one waiter preserves shared work for another route`() = runTest {
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val probeCancelled = CompletableDeferred<Unit>()
        val service = service(
            probeDuration = {
                probeStarted.complete(Unit)
                try {
                    releaseProbe.await()
                    4_200L
                } finally {
                    if (!releaseProbe.isCompleted) probeCancelled.complete(Unit)
                }
            },
        )
        val post = animatedPost("shared-cancel")
        val cancelledWaiter = async { service.enrich(post) }
        val survivingWaiter = async { service.enrich(post) }
        runCurrent()
        probeStarted.await()

        cancelledWaiter.cancel()
        runCurrent()
        assertFalse(probeCancelled.isCompleted)
        releaseProbe.complete(Unit)
        advanceUntilIdle()

        assertTrue(cancelledWaiter.isCancelled)
        assertEquals(4_200L, survivingWaiter.await()?.durationMs)
        assertFalse(probeCancelled.isCompleted)
        service.close()
    }

    @Test
    fun `canceling the final waiter cancels the injected probe`() = runTest {
        val probeStarted = CompletableDeferred<Unit>()
        val probeCancelled = CompletableDeferred<Unit>()
        val service = service(
            probeDuration = {
                probeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    probeCancelled.complete(Unit)
                }
            },
        )
        val waiter = async { service.enrich(animatedPost("only-waiter")) }
        runCurrent()
        probeStarted.await()

        waiter.cancel()
        advanceUntilIdle()

        assertTrue(waiter.isCancelled)
        assertTrue(probeCancelled.isCompleted)
        service.close()
    }

    @Test
    fun `known duration bypasses provider and probe`() = runTest {
        var providerChecks = 0
        var providerCalls = 0
        var probes = 0
        val service = service(
            hasProviderDurationResolver = {
                providerChecks += 1
                true
            },
            resolveProviderDuration = {
                providerCalls += 1
                DurationMetadataSourceResult.Unsupported
            },
            probeDuration = {
                probes += 1
                null
            },
        )

        val result = service.enrich(animatedPost("known").copy(durationMs = 7_500L))

        assertEquals(7_500L, result?.durationMs)
        assertEquals(0, providerChecks)
        assertEquals(0, providerCalls)
        assertEquals(0, probes)
        service.close()
    }

    @Test
    fun `preview-only autoplay clip settles without provider hydration or probe`() = runTest {
        var providerCalls = 0
        var probes = 0
        val previewOnly = animatedPost("preview-only").copy(
            full = null,
            media = listOf(
                ImageRef(
                    url = "https://example.test/preview-only-clip.mp4",
                    localPath = null,
                    mime = "video/mp4",
                )
            ),
        )
        val service = service(
            hasProviderDurationResolver = { false },
            resolveProviderDuration = {
                providerCalls += 1
                DurationMetadataSourceResult.Unsupported
            },
            probeDuration = {
                probes += 1
                3_000L
            },
        )

        assertNull(enrich(service, previewOnly))
        assertEquals(0, providerCalls)
        assertEquals(0, probes)
        service.close()
    }

    @Test
    fun `existing authoritative full video skips optional provider resolution`() = runTest {
        var providerCalls = 0
        var probes = 0
        val service = service(
            hasProviderDurationResolver = { true },
            resolveProviderDuration = {
                providerCalls += 1
                DurationMetadataSourceResult.Known(9_000L)
            },
            probeDuration = {
                probes += 1
                4_800L
            },
        )

        assertEquals(4_800L, enrich(service, animatedPost("direct-video"))?.durationMs)
        assertEquals(0, providerCalls)
        assertEquals(1, probes)
        service.close()
    }

    @Test
    fun `provider known duration skips native probe`() = runTest {
        var providerCalls = 0
        var probes = 0
        val post = animatedPost("provider-known").copy(full = null)
        val service = service(
            hasProviderDurationResolver = { true },
            resolveProviderDuration = {
                providerCalls += 1
                DurationMetadataSourceResult.Known(8_100L)
            },
            probeDuration = {
                probes += 1
                null
            },
        )

        assertEquals(8_100L, enrich(service, post)?.durationMs)
        assertEquals(1, providerCalls)
        assertEquals(0, probes)
        service.close()
    }

    @Test
    fun `provider authoritative video is the only provider media sent to probe`() = runTest {
        val authoritative = ImageRef(
            url = "https://example.test/provider-full.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        var probedPost: Post? = null
        val service = service(
            hasProviderDurationResolver = { true },
            resolveProviderDuration = {
                DurationMetadataSourceResult.AuthoritativeMedia(authoritative)
            },
            probeDuration = { post ->
                probedPost = post
                6_400L
            },
        )

        val result = enrich(service, animatedPost("provider-media").copy(full = null))

        assertEquals(6_400L, result?.durationMs)
        assertEquals(authoritative, probedPost?.full)
        service.close()
    }

    @Test
    fun `unsupported Hitomi animated gallery does no full-gallery provider or probe work`() = runTest {
        var providerCalls = 0
        var probes = 0
        val animatedWebpCover = ImageRef(
            url = "https://a.hitomi.la/webp/gallery-cover.webp",
            localPath = null,
            mime = "image/webp",
            isAnimated = true,
        )
        val post = testPost(
            source = SourceKey.HITOMI,
            sourcePostId = "4042375",
            preview = animatedWebpCover,
            full = null,
            media = emptyList(),
            mediaCount = 44,
        )
        val service = service(
            hasProviderDurationResolver = { false },
            resolveProviderDuration = {
                providerCalls += 1
                DurationMetadataSourceResult.Unsupported
            },
            probeDuration = {
                probes += 1
                null
            },
        )

        assertNull(enrich(service, post))
        assertEquals(0, providerCalls)
        assertEquals(0, probes)
        service.close()
    }

    @Test
    fun `complete acquisition is cancelled at its operation timeout`() = runTest {
        val probeStarted = CompletableDeferred<Unit>()
        val probeCancelled = CompletableDeferred<Unit>()
        val service = service(
            operationTimeoutMs = 100L,
            probeDuration = {
                probeStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    probeCancelled.complete(Unit)
                }
            },
        )
        val result = async { service.enrich(animatedPost("timeout")) }
        runCurrent()
        probeStarted.await()

        advanceTimeBy(101L)
        advanceUntilIdle()

        assertNull(result.await())
        assertTrue(probeCancelled.isCompleted)
        service.close()
    }

    @Test
    fun `same identity refresh reapplies cached duration without another probe`() = runTest {
        var probes = 0
        var posts = listOf(animatedPost("refreshed"))
        val applied = mutableListOf<AnimatedDurationEnrichment>()
        val service = service(
            probeDuration = {
                probes += 1
                4_200L
            },
        )
        val lane = AnimatedDurationEnrichmentLane(
            scope = this,
            enricher = service,
            currentIdentity = { "same-query" },
            currentPosts = { posts },
            applyEnrichments = { _, enrichments ->
                applied += enrichments
                val durations = enrichments.associateBy(AnimatedDurationEnrichment::postId)
                posts = posts.map { post ->
                    durations[post.id]?.let { post.copy(durationMs = it.durationMs) } ?: post
                }
            },
        )

        lane.request("same-query")
        advanceUntilIdle()
        posts = listOf(animatedPost("refreshed"))
        lane.request("same-query")
        advanceUntilIdle()

        assertEquals(listOf(4_200L, 4_200L), applied.map(AnimatedDurationEnrichment::durationMs))
        assertEquals(1, probes)
        service.close()
    }

    private fun kotlinx.coroutines.test.TestScope.service(
        hasProviderDurationResolver: (Post) -> Boolean = { false },
        resolveProviderDuration: suspend (Post) -> DurationMetadataSourceResult = {
            DurationMetadataSourceResult.Unsupported
        },
        probeDuration: suspend (Post) -> Long?,
        clock: () -> Long = { 0L },
        successCacheSize: Int = 128,
        negativeCacheSize: Int = 128,
        negativeTtlMs: Long = 300_000L,
        maxConcurrentWork: Int = 1,
        operationTimeoutMs: Long = 12_000L,
    ): AnimatedDurationEnrichmentService {
        return AnimatedDurationEnrichmentService(
            hasProviderDurationResolver = hasProviderDurationResolver,
            resolveProviderDuration = resolveProviderDuration,
            probeDurationMs = probeDuration,
            clock = clock,
            successCacheSize = successCacheSize,
            negativeCacheSize = negativeCacheSize,
            negativeTtlMs = negativeTtlMs,
            maxConcurrentWork = maxConcurrentWork,
            operationTimeoutMs = operationTimeoutMs,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.enrich(
        service: AnimatedDurationEnrichmentService,
        post: Post,
    ): AnimatedDurationEnrichment? {
        val result = async { service.enrich(post) }
        advanceUntilIdle()
        return result.await()
    }

    private fun animatedPost(sourcePostId: String): Post {
        return testPost(
            sourcePostId = sourcePostId,
            preview = ImageRef(
                url = "https://example.test/$sourcePostId-preview.mp4",
                localPath = null,
                mime = "video/mp4",
            ),
            full = ImageRef(
                url = "https://example.test/$sourcePostId.mp4",
                localPath = null,
                mime = "video/mp4",
            ),
        )
    }
}
