package com.theoriacodex.app.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

/** Local animation time; pausing never includes the elapsed time spent off screen. */
internal class LoopingAnimationClock(private val durationMs: Long) {
    private var position = 0.0
    private var completed = false
    private var previousFrameNanos: Long? = null

    init {
        require(durationMs > 0L)
    }

    val positionMs: Long
        get() = position.toLong()

    fun seekTo(positionMs: Long) {
        position = positionMs.coerceIn(0L, durationMs - 1).toDouble()
        completed = false
        resetFrameTime()
    }

    fun resetFrameTime() {
        previousFrameNanos = null
    }

    fun advanceTo(frameNanos: Long, rate: Float, loop: Boolean = true, availableUntilMs: Long = Long.MAX_VALUE): Boolean {
        require(rate.isFinite() && rate > 0f)
        if (completed && !loop) return false
        val previous = previousFrameNanos
        previousFrameNanos = frameNanos
        if (previous != null) {
            val elapsedMs = (frameNanos - previous).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
            val next = position + elapsedMs * rate
            val availableEnd = availableUntilMs.coerceIn(1L, durationMs)
            if (availableEnd < durationMs) {
                // Hold at the decoded boundary; a seek beyond it waits at its requested position.
                position = maxOf(position, minOf(next, (availableEnd - 1).toDouble()))
                return false
            }
            if (!loop && next >= durationMs) {
                position = (durationMs - 1).toDouble()
                if (!completed) { completed = true; return true }
            } else { position = next % durationMs; completed = false }
        }
        return false
    }
}

/** Only the image/timeline consumes frame changes; route state owns playback commands. */
internal class AnimationPlaybackState(durationMs: Long) {
    private val clock = LoopingAnimationClock(durationMs)
    var positionMs by mutableLongStateOf(0L)
        private set

    fun seekTo(positionMs: Long) {
        clock.seekTo(positionMs)
        this.positionMs = clock.positionMs
    }

    fun resetFrameTime() = clock.resetFrameTime()

    fun advanceTo(frameNanos: Long, rate: Float, loop: Boolean = true, availableUntilMs: Long = Long.MAX_VALUE): Boolean {
        val completed = clock.advanceTo(frameNanos, rate, loop, availableUntilMs)
        positionMs = clock.positionMs
        return completed
    }
}

@Composable
internal fun AnimatePlayback(
    state: AnimationPlaybackState,
    enabled: Boolean,
    rate: Float,
    loop: Boolean = true,
    onCompleted: () -> Unit = {},
    availableUntilMs: Long = Long.MAX_VALUE,
) {
    val latestCompletion by androidx.compose.runtime.rememberUpdatedState(onCompleted)
    val latestAvailableUntilMs by androidx.compose.runtime.rememberUpdatedState(availableUntilMs)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner, enabled, rate, loop) {
        if (!enabled) return@LaunchedEffect
        animatePlaybackWhileStarted(
            state, lifecycleOwner.lifecycle, rate, loop,
            availableUntilMs = { latestAvailableUntilMs },
            onCompleted = { latestCompletion() },
        )
    }
}

internal suspend fun animatePlaybackWhileStarted(
    state: AnimationPlaybackState,
    lifecycle: Lifecycle,
    rate: Float,
    loop: Boolean = true,
    availableUntilMs: () -> Long = { Long.MAX_VALUE },
    onCompleted: () -> Unit = {},
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        state.resetFrameTime()
        while (isActive) {
            if (withFrameNanos { frameNanos -> state.advanceTo(frameNanos, rate, loop, availableUntilMs()) }) onCompleted()
        }
    }
}

/** Exclusive frame ends allow seeking and late frames to jump directly to the right image. */
internal fun animationFrameIndexAt(positionMs: Long, frameEndsMs: LongArray): Int {
    require(frameEndsMs.isNotEmpty())
    val found = frameEndsMs.binarySearch(positionMs.coerceAtLeast(0L))
    return (if (found >= 0) found + 1 else -found - 1).coerceAtMost(frameEndsMs.lastIndex)
}

private const val NANOS_PER_MILLISECOND = 1_000_000.0
