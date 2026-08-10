@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal const val FEED_PREVIEW_MAX_IDLE_PLAYERS = 8
internal const val FEED_PREVIEW_IDLE_TIMEOUT_MS = 30_000L
internal const val FEED_PREVIEW_EXCESS_RELEASE_DELAY_MS = 5_000L
internal const val FEED_PREVIEW_RELEASE_SPACING_MS = 1_000L

/**
 * Application-owned preview players outlive individual lazy-grid items.
 *
 * Returning a lease only pauses the player. Slow codec/thread release is paced after the UI path,
 * so disposing a route cannot synchronously release every visible player one by one.
 */
internal class FeedPreviewPlayerPool(
    context: Context,
    private val infrastructure: VideoPlaybackInfrastructure,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val slots = ReusableVideoSlotPool<ExoPlayer, VideoPlaybackIdentity>(
        maxIdleResources = FEED_PREVIEW_MAX_IDLE_PLAYERS,
        idleTimeoutMs = FEED_PREVIEW_IDLE_TIMEOUT_MS,
        clock = clock,
        createResource = ::createPlayer,
    )
    private var cleanupScheduled = false
    private val cleanupRunnable = Runnable {
        cleanupScheduled = false
        slots.pollExpired()?.let { player ->
            traceMediaSection(MediaTraceSections.PREVIEW_PLAYER_RELEASE) { player.release() }
        }
        scheduleCleanup(FEED_PREVIEW_RELEASE_SPACING_MS)
    }

    fun acquire(
        location: String,
        headers: Map<String, String>,
    ): FeedPreviewPlayerLease {
        checkMainThread()
        val request = infrastructure.bind(location, headers)
        val slotLease = slots.acquire(request.identity)
        if (slotLease.requiresBinding || slotLease.resource.playerError != null) {
            val mediaSource = request.mediaSourceFactory.createMediaSource(request.mediaItem)
            slotLease.resource.setMediaSource(mediaSource, true)
            slotLease.resource.prepare()
        }
        scheduleCleanup()
        return FeedPreviewPlayerLease(slotLease)
    }

    fun recycle(
        lease: FeedPreviewPlayerLease,
        retainBinding: Boolean,
    ) {
        checkMainThread()
        runCatching {
            lease.player.playWhenReady = false
            lease.player.pause()
        }
        if (slots.recycle(lease.slotLease, retainBinding)) scheduleCleanup()
    }

    internal fun activePlayerCount(): Int = slots.activeResourceCount

    internal fun idlePlayerCount(): Int = slots.idleResourceCount

    private fun createPlayer(): ExoPlayer {
        return traceMediaSection(MediaTraceSections.PREVIEW_PLAYER_CREATE) {
            ExoPlayer.Builder(appContext)
                .setLoadControl(infrastructure.loadControl(VideoPlaybackProfile.FEED_PREVIEW))
                .build()
                .apply {
                    addAnalyticsListener(MediaLoadTraceListener)
                    repeatMode = Player.REPEAT_MODE_ONE
                    setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                }
        }
    }

    private fun scheduleCleanup(minimumDelayMs: Long = 0L) {
        val nextDelayMs = slots.nextCleanupDelayMs() ?: run {
            if (cleanupScheduled) handler.removeCallbacks(cleanupRunnable)
            cleanupScheduled = false
            return
        }
        if (cleanupScheduled) handler.removeCallbacks(cleanupRunnable)
        cleanupScheduled = true
        handler.postDelayed(cleanupRunnable, maxOf(minimumDelayMs, nextDelayMs))
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Feed preview players must be leased on the main thread"
        }
    }
}

internal class FeedPreviewPlayerLease internal constructor(
    internal val slotLease: ReusableVideoSlotPool.Lease<ExoPlayer>,
) {
    val player: ExoPlayer
        get() = slotLease.resource
}

/** Pure lease/retention policy kept separate from Media3 so reuse remains directly testable. */
internal class ReusableVideoSlotPool<Resource : Any, Identity : Any>(
    private val maxIdleResources: Int,
    private val idleTimeoutMs: Long,
    private val clock: () -> Long,
    private val createResource: () -> Resource,
) {
    internal data class Lease<Resource : Any>(
        val resource: Resource,
        internal val token: Long,
        val requiresBinding: Boolean,
    )

    private class Slot<Resource : Any, Identity : Any>(
        val resource: Resource,
        var boundIdentity: Identity? = null,
        var token: Long = 0L,
        var active: Boolean = false,
        var idleSinceMs: Long = 0L,
    )

    private val slotsByResource = IdentityHashMap<Resource, Slot<Resource, Identity>>()
    private val idleSlots = ArrayDeque<Slot<Resource, Identity>>()

    val activeResourceCount: Int
        get() = slotsByResource.size - idleSlots.size

    val idleResourceCount: Int
        get() = idleSlots.size

    init {
        require(maxIdleResources >= 0) { "Idle resource bound must not be negative" }
        require(idleTimeoutMs > 0L) { "Idle timeout must be positive" }
    }

    fun acquire(identity: Identity): Lease<Resource> {
        val slot = removeFirstIdle { candidate -> candidate.boundIdentity == identity }
            ?: idleSlots.pollFirst()
            ?: Slot<Resource, Identity>(createResource()).also { created ->
                slotsByResource[created.resource] = created
            }
        check(!slot.active) { "A reusable slot cannot be leased twice" }
        val requiresBinding = slot.boundIdentity != identity
        slot.boundIdentity = identity
        slot.active = true
        slot.token += 1L
        return Lease(slot.resource, slot.token, requiresBinding)
    }

    fun recycle(lease: Lease<Resource>, retainBinding: Boolean = true): Boolean {
        val slot = slotsByResource[lease.resource] ?: return false
        if (!slot.active || slot.token != lease.token) return false
        slot.active = false
        if (!retainBinding) slot.boundIdentity = null
        slot.idleSinceMs = clock()
        idleSlots.addLast(slot)
        return true
    }

    fun pollExpired(nowMs: Long = clock()): Resource? {
        if (activeResourceCount > 0) return null
        val candidate = when {
            idleSlots.size > maxIdleResources -> idleSlots.pollFirst()
            idleSlots.firstOrNull()?.let { nowMs - it.idleSinceMs >= idleTimeoutMs } == true ->
                idleSlots.pollFirst()
            else -> null
        } ?: return null
        slotsByResource.remove(candidate.resource)
        return candidate.resource
    }

    fun nextCleanupDelayMs(nowMs: Long = clock()): Long? {
        if (idleSlots.isEmpty() || activeResourceCount > 0) return null
        if (idleSlots.size > maxIdleResources) return FEED_PREVIEW_EXCESS_RELEASE_DELAY_MS
        val oldest = idleSlots.first()
        return (idleTimeoutMs - (nowMs - oldest.idleSinceMs)).coerceAtLeast(0L)
    }

    private inline fun removeFirstIdle(
        predicate: (Slot<Resource, Identity>) -> Boolean,
    ): Slot<Resource, Identity>? {
        val iterator = idleSlots.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            if (predicate(slot)) {
                iterator.remove()
                return slot
            }
        }
        return null
    }
}
