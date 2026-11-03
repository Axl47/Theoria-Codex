package com.theoriacodex.app.media

import com.theoriacodex.domain.adapter.DurationMetadataSourceAdapter
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.withTimeoutOrNull

internal class MediaDurationAcquisitionEngine(
    private val hasProviderDurationResolver: (Post) -> Boolean,
    private val resolveProviderDuration: suspend (Post) -> DurationMetadataSourceResult,
    private val probeDuration: suspend (Post) -> MediaDurationState,
    private val clock: () -> Long = System::currentTimeMillis,
    private val operationTimeoutMs: Long = DEFAULT_DURATION_ACQUISITION_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_DURATION_ACQUISITION_RETRY_MS,
    private val traceRecorder: MediaDurationTraceRecorder = AndroidMediaDurationTraceRecorder,
    private val pacer: DurationRequestPacer = DurationRequestPacer(clock),
) : MediaDurationAcquirer {
    constructor(
        registry: SourceAdapterRegistry,
        probe: BoundedMediaDurationProbe,
        clock: () -> Long = System::currentTimeMillis,
        traceRecorder: MediaDurationTraceRecorder = AndroidMediaDurationTraceRecorder,
    ) : this(
        hasProviderDurationResolver = { post ->
            registry.adapterFor(post.id.source) is DurationMetadataSourceAdapter
        },
        resolveProviderDuration = { post ->
            val adapter = registry.adapterFor(post.id.source) as? DurationMetadataSourceAdapter
            adapter?.resolveDurationMetadata(post) ?: DurationMetadataSourceResult.Unsupported
        },
        probeDuration = probe::probe,
        clock = clock,
        traceRecorder = traceRecorder,
    )

    init {
        require(operationTimeoutMs > 0L) { "Duration acquisition timeout must be positive" }
        require(retryDelayMs > 0L) { "Duration acquisition retry delay must be positive" }
    }

    /** Answers that need no network, so they never wait behind paced lookups. */
    override fun settleWithoutWork(post: Post): MediaDurationState? {
        return when (plan(post)) {
            is DurationAcquisitionPlan.AlreadyKnown -> MediaDurationState.Known(
                durationMs = requireNotNull(animatedDurationMs(post)),
                provenance = MediaDurationProvenance.PROVIDER,
            )
            is DurationAcquisitionPlan.Unsupported -> MediaDurationState.Unsupported(
                MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
            )
            else -> null
        }
    }

    /** The pacing wait sits outside the timeout, which only bounds time spent on the network. */
    override suspend fun acquire(post: Post): MediaDurationState {
        settleWithoutWork(post)?.let { return it }
        var result: MediaDurationState = retryable(MediaDurationFailureReason.RATE_LIMITED)
        repeat(MAX_THROTTLED_ATTEMPTS) {
            pacer.awaitTurn(post.id.source)
            result = withTimeoutOrNull(operationTimeoutMs) {
                acquireWithinTimeout(post)
            } ?: retryable(MediaDurationFailureReason.TIMEOUT)
            val throttled = result as? MediaDurationState.RetryableFailure
            if (throttled?.reason != MediaDurationFailureReason.RATE_LIMITED) return result
            pacer.onThrottled(post.id.source)
        }
        return result
    }

    private fun plan(post: Post): DurationAcquisitionPlan {
        return planDurationAcquisition(
            DurationAcquisitionFacts(
                knownDurationMs = animatedDurationMs(post),
                persistedState = null,
                hasAuthoritativeFullVideo = authoritativeDurationProbeRef(post) != null,
                hasProviderDurationResolver = hasProviderDurationResolver(post),
            ),
        )
    }

    private suspend fun acquireWithinTimeout(post: Post): MediaDurationState {
        return when (plan(post)) {
            DurationAcquisitionPlan.ProbeAuthoritativeMedia -> probe(post)
            DurationAcquisitionPlan.AskProvider -> acquireFromProvider(post)
            is DurationAcquisitionPlan.AlreadyKnown,
            is DurationAcquisitionPlan.Unsupported,
            is DurationAcquisitionPlan.UsePersisted,
            -> error("Settled before any acquisition starts")
        }
    }

    private suspend fun acquireFromProvider(post: Post): MediaDurationState {
        traceRecorder.providerResolve()
        val result = runCatchingPreservingCancellation { resolveProviderDuration(post) }
            .getOrNull()
            ?: return retryable(MediaDurationFailureReason.PROVIDER_FAILURE)
        return when (result) {
            is DurationMetadataSourceResult.Known -> MediaDurationState.Known(
                durationMs = result.durationMs,
                provenance = MediaDurationProvenance.PROVIDER,
            )
            is DurationMetadataSourceResult.AuthoritativeMedia -> {
                val probePost = post.copy(full = result.media, media = listOf(result.media))
                if (authoritativeDurationProbeRef(probePost) == null) {
                    MediaDurationState.Unsupported(MediaDurationUnsupportedReason.PROVIDER_UNSUPPORTED)
                } else {
                    probe(probePost)
                }
            }
            DurationMetadataSourceResult.Unsupported -> MediaDurationState.Unsupported(
                MediaDurationUnsupportedReason.PROVIDER_UNSUPPORTED,
            )
            DurationMetadataSourceResult.RetryableFailure -> retryable(
                MediaDurationFailureReason.PROVIDER_FAILURE,
            )
        }
    }

    private suspend fun probe(post: Post): MediaDurationState {
        traceRecorder.probe()
        return runCatchingPreservingCancellation { probeDuration(post) }
            .getOrElse { retryable(MediaDurationFailureReason.TRANSPORT_FAILURE) }
    }

    private fun retryable(reason: MediaDurationFailureReason): MediaDurationState.RetryableFailure {
        return MediaDurationState.RetryableFailure(
            retryAtEpochMs = clock() + retryDelayMs,
            reason = reason,
        )
    }
}

internal const val DEFAULT_DURATION_ACQUISITION_TIMEOUT_MS = 12_000L
private const val DEFAULT_DURATION_ACQUISITION_RETRY_MS = 5L * 60L * 1_000L
private const val MAX_THROTTLED_ATTEMPTS = 3
