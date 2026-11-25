package com.theoriacodex.app.search

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import com.theoriacodex.app.diagnostics.debugDiagnosticsEnabled
import com.theoriacodex.app.media.PostMediaKind
import com.theoriacodex.app.media.awaitsVideoStream
import com.theoriacodex.app.media.mediaKind
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.delay

internal const val FEED_AUTOPLAY_STALL_MS = 2_000L
private const val FEED_AUTOPLAY_POLL_MS = 500L
private const val FEED_AUTOPLAY_AREA = "autoplay"

internal class FeedAutoplayProbe {
    var expectsVideo = false
    var hasVideo = false
    var playbackFailed = false
    var leaseWanted = false
    var leased = false

    fun gates(onScreen: Boolean, focused: Boolean, inWindow: Boolean, inFeed: Boolean?, started: Boolean) = FeedAutoplayGates(
        onScreen = onScreen, focused = focused, inWindow = inWindow, inFeed = inFeed, started = started,
        expectsVideo = expectsVideo, hasVideo = hasVideo, playbackFailed = playbackFailed, leaseWanted = leaseWanted, leased = leased,
    )
}

internal data class FeedAutoplayGates(
    val onScreen: Boolean,
    val focused: Boolean,
    val inWindow: Boolean,
    val inFeed: Boolean?,
    val started: Boolean,
    val expectsVideo: Boolean,
    val hasVideo: Boolean,
    val playbackFailed: Boolean,
    val leaseWanted: Boolean,
    val leased: Boolean,
) {
    val stalled: Boolean get() = onScreen && focused && expectsVideo && !leased
}

internal class FeedAutoplayStallTrace(private val label: String, private val stallMs: Long = FEED_AUTOPLAY_STALL_MS) {
    private var stalledSinceMs: Long? = null
    private var reported: FeedAutoplayGates? = null

    fun observe(gates: FeedAutoplayGates, nowMs: Long): String? {
        if (!gates.stalled) {
            stalledSinceMs = null
            return reported?.let { "$label cleared $gates" }.also { reported = null }
        }
        val since = stalledSinceMs ?: nowMs.also { stalledSinceMs = it }
        if (nowMs - since < stallMs || gates == reported) return null
        reported = gates
        return "$label stalled ${nowMs - since} ms $gates"
    }

    fun close(): String? = reported?.let { "$label left while stalled" }
}

internal fun Post.expectsFeedVideo(): Boolean =
    awaitsVideoStream || id.source.videoUrlComesFromPostDetails() ||
        (listOfNotNull(preview, full) + media).any { ref -> mediaKind(ref) == PostMediaKind.VIDEO }

@Composable
internal fun rememberFeedAutoplayProbe(postId: PostId): FeedAutoplayProbe? {
    if (!debugDiagnosticsEnabled) return null
    return remember(postId) { FeedAutoplayProbe() }
}

@Composable
internal fun FeedAutoplayVideoEffect(probe: FeedAutoplayProbe?, videoRef: ImageRef?, post: Post, playbackFailed: Boolean) {
    probe ?: return
    SideEffect {
        probe.hasVideo = videoRef != null
        probe.expectsVideo = videoRef != null || post.expectsFeedVideo()
        probe.playbackFailed = playbackFailed
    }
}

@Composable
internal fun FeedAutoplayLeaseWantedEffect(probe: FeedAutoplayProbe?, wanted: Boolean) {
    probe ?: return
    SideEffect { probe.leaseWanted = wanted }
    DisposableEffect(probe) { onDispose { probe.leaseWanted = false } }
}

@Composable
internal fun FeedAutoplayTraceEffect(
    postId: PostId,
    probe: FeedAutoplayProbe?,
    boundsInWindow: () -> Rect?,
    inWindow: () -> Boolean,
    inFeed: () -> Boolean?,
    started: () -> Boolean,
) {
    probe ?: return
    val window = LocalWindowInfo.current
    LaunchedEffect(postId, probe) {
        val trace = FeedAutoplayStallTrace("${postId.source}:${postId.sourcePostId}")
        try {
            while (true) {
                val size = window.containerSize
                val onScreen = boundsInWindow()?.overlaps(Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())) == true
                val gates = probe.gates(onScreen, window.isWindowFocused, inWindow(), inFeed(), started())
                trace.observe(gates, SystemClock.uptimeMillis())?.let { line -> Diagnostics.report(FEED_AUTOPLAY_AREA, line) }
                delay(FEED_AUTOPLAY_POLL_MS)
            }
        } finally {
            trace.close()?.let { line -> Diagnostics.report(FEED_AUTOPLAY_AREA, line) }
        }
    }
}
