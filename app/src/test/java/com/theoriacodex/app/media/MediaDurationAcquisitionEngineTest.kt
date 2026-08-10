package com.theoriacodex.app.media

import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDurationAcquisitionEngineTest {
    @Test
    fun `existing authoritative video probes before optional provider`() = runTest {
        var providerCalls = 0
        var probeCalls = 0
        val engine = engine(
            hasProvider = { true },
            resolveProvider = {
                providerCalls += 1
                DurationMetadataSourceResult.Known(9_000L)
            },
            probe = {
                probeCalls += 1
                known(4_000L)
            },
        )

        assertEquals(known(4_000L), engine.acquire(post("direct")))
        assertEquals(0, providerCalls)
        assertEquals(1, probeCalls)
    }

    @Test
    fun `provider known metadata skips probe and retains provenance`() = runTest {
        var probes = 0
        val engine = engine(
            hasProvider = { true },
            resolveProvider = { DurationMetadataSourceResult.Known(9_000L) },
            probe = {
                probes += 1
                known(1_000L)
            },
        )

        assertEquals(
            MediaDurationState.Known(9_000L, MediaDurationProvenance.PROVIDER),
            engine.acquire(post("provider").copy(full = null, media = emptyList())),
        )
        assertEquals(0, probes)
    }

    @Test
    fun `unsupported capability becomes a terminal typed decision`() = runTest {
        val engine = engine(
            hasProvider = { true },
            resolveProvider = { DurationMetadataSourceResult.Unsupported },
            probe = { error("Probe should not run") },
        )

        assertEquals(
            MediaDurationState.Unsupported(MediaDurationUnsupportedReason.PROVIDER_UNSUPPORTED),
            engine.acquire(post("unsupported").copy(full = null, media = emptyList())),
        )
    }

    @Test
    fun `provider retry remains retryable instead of becoming unsupported`() = runTest {
        val engine = engine(
            hasProvider = { true },
            resolveProvider = { DurationMetadataSourceResult.RetryableFailure },
            probe = { error("Probe should not run") },
        )

        assertEquals(
            MediaDurationState.RetryableFailure(110L, MediaDurationFailureReason.PROVIDER_FAILURE),
            engine.acquire(post("retry").copy(full = null, media = emptyList())),
        )
    }

    @Test
    fun `overall timeout cancels probe and becomes a bounded retry`() = runTest {
        var cancelled = false
        val engine = engine(
            hasProvider = { false },
            resolveProvider = { DurationMetadataSourceResult.Unsupported },
            probe = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            timeoutMs = 10L,
        )

        assertEquals(
            MediaDurationState.RetryableFailure(110L, MediaDurationFailureReason.TIMEOUT),
            engine.acquire(post("timeout")),
        )
        assertEquals(true, cancelled)
    }

    private fun engine(
        hasProvider: (Post) -> Boolean,
        resolveProvider: suspend (Post) -> DurationMetadataSourceResult,
        probe: suspend (Post) -> MediaDurationState,
        timeoutMs: Long = 1_000L,
    ): MediaDurationAcquisitionEngine {
        return MediaDurationAcquisitionEngine(
            hasProviderDurationResolver = hasProvider,
            resolveProviderDuration = resolveProvider,
            probeDuration = probe,
            clock = { 100L },
            operationTimeoutMs = timeoutMs,
            retryDelayMs = 10L,
            traceRecorder = NoOpMediaDurationTraceRecorder,
        )
    }

    private fun known(durationMs: Long): MediaDurationState.Known {
        return MediaDurationState.Known(durationMs, MediaDurationProvenance.CONTAINER_PROBE)
    }

    private fun post(id: String): Post {
        val media = ImageRef(
            url = "https://example.test/$id.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        return Post(
            id = PostId(SourceKey.HITOMI, id),
            preview = media,
            full = media,
            media = listOf(media),
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
