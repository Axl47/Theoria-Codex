package com.theoriacodex.app.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Trace
import com.theoriacodex.app.R
import com.theoriacodex.app.media.AnimatedDurationEnrichment
import com.theoriacodex.app.media.AnimatedDurationEnrichmentLane
import com.theoriacodex.app.media.AnimatedDurationEnrichmentService
import com.theoriacodex.app.media.NoOpMediaDurationTraceRecorder
import com.theoriacodex.domain.adapter.DurationMetadataSourceResult
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Explicit benchmark-only signal; the production app never registers this receiver. */
class BenchmarkDurationStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_BENCHMARK_DURATION_START) {
            BenchmarkDurationStartSignal.request()
        }
    }
}

internal object BenchmarkDurationStartSignal {
    val generation = MutableStateFlow(0L)

    fun reset() {
        generation.value = 0L
    }

    fun request() {
        generation.value += 1L
    }
}

/**
 * Drives the pre-rebuild route lane and service against the bundled MP4. This keeps the baseline
 * offline while retaining the current batching, worker contention, probing, and immutable post-list
 * publication that compete with visible autoplay in production.
 */
internal class BenchmarkDurationWorkload(
    scope: CoroutineScope,
    resources: Resources,
    initialPosts: List<Post>,
    private val currentPosts: () -> List<Post>,
    private val publishPosts: (List<Post>) -> Unit,
) : AutoCloseable {
    private val initialPostsById = initialPosts.associateBy(Post::id)
    private val service = AnimatedDurationEnrichmentService(
        hasProviderDurationResolver = { true },
        resolveProviderDuration = { post ->
            traceDurationSuspend(TRACE_DURATION_RESOLVE) {
                initialPostsById[post.id]?.full
                    ?.let(DurationMetadataSourceResult::AuthoritativeMedia)
                    ?: DurationMetadataSourceResult.Unsupported
            }
        },
        probeDurationMs = {
            traceDurationSuspend(TRACE_DURATION_PROBE) {
                probeBundledDurationMs(resources)
            }
        },
        traceRecorder = NoOpMediaDurationTraceRecorder,
    )
    private val lane = AnimatedDurationEnrichmentLane(
        scope = scope,
        enricher = service,
        currentIdentity = { DURATION_WORKLOAD_IDENTITY },
        currentPosts = currentPosts,
        applyEnrichments = ::applyEnrichments,
    )
    private var started = false
    private var settled = false

    fun start() {
        if (started) return
        started = true
        recordDurationTraceEvent(TRACE_DURATION_DEMAND)
        beginDurationAsyncTrace()
        lane.request(DURATION_WORKLOAD_IDENTITY)
    }

    override fun close() {
        if (started && !settled) endDurationAsyncTrace()
        service.close()
    }

    private fun applyEnrichments(
        identity: String,
        enrichments: List<AnimatedDurationEnrichment>,
    ) {
        if (identity != DURATION_WORKLOAD_IDENTITY || enrichments.isEmpty()) return
        val durationsByPostId = enrichments.associate { result -> result.postId to result.durationMs }
        val updatedPosts = currentPosts().map { post ->
            durationsByPostId[post.id]?.let { durationMs -> post.copy(durationMs = durationMs) } ?: post
        }
        traceDurationSection(TRACE_DURATION_PUBLISH) {
            publishPosts(updatedPosts)
        }
        if (!settled && updatedPosts.size == SEARCH_POST_COUNT && updatedPosts.all { it.durationMs != null }) {
            settled = true
            recordDurationTraceEvent(TRACE_DURATION_SETTLED)
            endDurationAsyncTrace()
        }
    }
}

private suspend fun probeBundledDurationMs(resources: Resources): Long? = withContext(Dispatchers.IO) {
    resources.openRawResourceFd(R.raw.benchmark_loop).use { asset ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { durationMs -> durationMs > 0L }
        } finally {
            retriever.release()
        }
    }
}

private suspend inline fun <T> traceDurationSuspend(
    name: String,
    crossinline block: suspend () -> T,
): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private inline fun <T> traceDurationSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private fun recordDurationTraceEvent(name: String) {
    Trace.beginSection(name)
    Trace.endSection()
}

private fun beginDurationAsyncTrace() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.beginAsyncSection(TRACE_DURATION_WORKLOAD, DURATION_TRACE_COOKIE)
    }
}

private fun endDurationAsyncTrace() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.endAsyncSection(TRACE_DURATION_WORKLOAD, DURATION_TRACE_COOKIE)
    }
}

internal const val ACTION_BENCHMARK_DURATION_START =
    "com.theoriacodex.action.BENCHMARK_DURATION_START"
internal const val DURATION_STATUS_TAG = "benchmark_duration_status"
internal const val DURATION_SETTLED_DESCRIPTION = "Settled 24/24"
internal const val TRACE_DURATION_DEMAND = "TheoriaDurationDemand"
internal const val TRACE_DURATION_RESOLVE = "TheoriaDurationResolve"
internal const val TRACE_DURATION_PROBE = "TheoriaDurationProbe"
internal const val TRACE_DURATION_PUBLISH = "TheoriaDurationPublish"
internal const val TRACE_DURATION_SETTLED = "TheoriaDurationSettled"
internal const val TRACE_DURATION_WORKLOAD = "TheoriaDurationWorkload"
private const val DURATION_WORKLOAD_IDENTITY = "benchmark-duration-workload"
private const val DURATION_TRACE_COOKIE = 1
