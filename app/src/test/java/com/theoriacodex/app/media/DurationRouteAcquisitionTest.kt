package com.theoriacodex.app.media

import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real route demand -> coordinator -> acquisition engine -> bounded transport/parser integration. */
@OptIn(ExperimentalCoroutinesApi::class)
class DurationRouteAcquisitionTest {
    @Test
    fun `scrolling cancels real range acquisition and idle resumes without rewriting feed posts`() = runTest {
        val bytes = bundledMp4()
        val transport = RangeFixture(bytes)
        val probe = BoundedMediaDurationProbe(transport)
        val engine = MediaDurationAcquisitionEngine(
            hasProviderDurationResolver = { false },
            resolveProviderDuration = { DurationMetadataSourceResult.Unsupported },
            probeDuration = probe::probe,
            traceRecorder = NoOpMediaDurationTraceRecorder,
        )
        val coordinator = MediaDurationCoordinator(engine, parentScope = backgroundScope,
            traceRecorder = NoOpMediaDurationTraceRecorder)
        val route = MediaDurationRouteViewModel(coordinator, "search", backgroundScope)
        val posts = List(3) { animatedTestPost(sourcePostId = "range-$it") }
        val originalFirst = posts.first()
        route.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = false)
        route.synchronize("query", posts, resolveInBackground = true)
        route.onFilterChanged(true)
        route.onPostVisibilityChanged(originalFirst, true)
        runCurrent()
        assertEquals(0, transport.calls)

        route.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        runCurrent()
        assertEquals(1, transport.calls)
        advanceTimeBy(100)
        route.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = false)
        runCurrent()
        assertEquals(1, transport.cancellations)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, transport.calls)
        assertTrue(coordinator.states.value.values.none { it is MediaDurationState.Known })

        route.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, transport.calls)
        assertEquals(3, route.states.value.size)
        assertTrue(route.states.value.values.all {
            it == MediaDurationState.Known(2_000L, MediaDurationProvenance.CONTAINER_PROBE)
        })
        assertEquals(bytes.size * 3L, transport.returnedBytes)
        assertSame(originalFirst, posts.first())
        assertTrue(posts.all { it.durationMs == null })
        val settledCalls = transport.calls
        route.onPostVisibilityChanged(originalFirst, false)
        route.onPostVisibilityChanged(originalFirst, true)
        route.onEnvironmentChanged(lifecycleStarted = false, scrollIdle = true)
        route.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(settledCalls, transport.calls)
        coordinator.close()
    }

    private fun bundledMp4(): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, "app/src/benchmarkRelease/res/raw/benchmark_loop.mp4").readBytes()
    }
}

private class RangeFixture(private val media: ByteArray) : SourceHttpClient {
    var calls = 0
    var cancellations = 0
    var returnedBytes = 0L
    override suspend fun getBytes(url: String, query: Map<String, String>, headers: Map<String, String>,
        range: SourceByteRange?, maxBodyBytes: Int): SourceByteResponse {
        calls++
        var complete = false
        try {
            delay(250)
            val requested = requireNotNull(range)
            val start = requested.startInclusive.toInt()
            val end = minOf(requested.endInclusive + 1, media.size.toLong()).toInt()
            val bytes = media.copyOfRange(start, end)
            assertTrue(bytes.size <= maxBodyBytes && maxBodyBytes <= 256 * 1024)
            assertTrue(headers.containsKey("Referer"))
            returnedBytes += bytes.size
            complete = true
            return SourceByteResponse(206, bytes,
                mapOf("Content-Range" to listOf("bytes $start-${end - 1}/${media.size}")))
        } finally {
            if (!complete) cancellations++
        }
    }
    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse = error("Not used")
    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse = error("Not used")
}
