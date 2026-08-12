package com.theoriacodex.app.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Trace
import com.theoriacodex.app.R
import com.theoriacodex.app.media.DurationDemand
import com.theoriacodex.app.media.DurationDemandPriority
import com.theoriacodex.app.media.DurationDemandReason
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationProvenance
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * Drives the production duration coordinator against the bundled MP4 while remaining offline.
 * The baseline commit exercised the legacy path; the final run keeps the same posts and gestures
 * but now observes separate coordinator metadata instead of rewriting the feed list.
 */
internal class BenchmarkDurationWorkload(
    private val scope: CoroutineScope,
    resources: Resources,
    initialPosts: List<Post>,
) : AutoCloseable {
    private val posts = initialPosts
    private val coordinator = MediaDurationCoordinator(
        acquirer = { _ ->
            recordDurationTraceEvent(TRACE_DURATION_RESOLVE)
            traceDurationSuspend(TRACE_DURATION_PROBE) {
                probeBundledDurationMs(resources)?.let { durationMs ->
                    MediaDurationState.Known(
                        durationMs = durationMs,
                        provenance = MediaDurationProvenance.CONTAINER_PROBE,
                    )
                } ?: MediaDurationState.Unsupported(
                    com.theoriacodex.app.media.MediaDurationUnsupportedReason.UNSUPPORTED_CONTAINER,
                )
            }
        },
        parentScope = scope,
    )
    private var started = false

    val states: StateFlow<Map<com.theoriacodex.app.media.MediaDurationKey, MediaDurationState>> =
        coordinator.states

    fun start() {
        if (started) return
        started = true
        scope.launch {
            coordinator.updateEnvironment(
                identity = DURATION_WORKLOAD_IDENTITY,
                lifecycleStarted = true,
                scrollIdle = true,
            )
            val keys = posts.map(::mediaDurationKey)
            beginDurationBatchTrace()
            try {
                posts.zip(keys).forEach { (post, key) ->
                    coordinator.submit(
                        post = post,
                        demand = DurationDemand(
                            identity = DURATION_WORKLOAD_IDENTITY,
                            key = key,
                            priority = DurationDemandPriority.BACKGROUND_IDLE,
                            reason = DurationDemandReason.APPEND,
                        ),
                    )
                }
                coordinator.states.first { states ->
                    keys.all { key -> states[key] is MediaDurationState.Known }
                }
            } finally {
                endDurationBatchTrace()
            }
        }
    }

    override fun close() {
        coordinator.close()
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

private fun recordDurationTraceEvent(name: String) {
    Trace.beginSection(name)
    Trace.endSection()
}

private fun beginDurationBatchTrace() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.beginAsyncSection(TRACE_DURATION_BATCH, DURATION_BATCH_TRACE_COOKIE)
    }
}

private fun endDurationBatchTrace() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.endAsyncSection(TRACE_DURATION_BATCH, DURATION_BATCH_TRACE_COOKIE)
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
internal const val TRACE_DURATION_BATCH = "TheoriaDurationBatch"
private const val DURATION_WORKLOAD_IDENTITY = "benchmark-duration-workload"
private const val DURATION_BATCH_TRACE_COOKIE = 1
