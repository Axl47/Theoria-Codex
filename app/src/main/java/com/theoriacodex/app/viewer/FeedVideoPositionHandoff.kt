package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import java.io.Closeable

/** Main-thread, one-time handoffs survive either order of viewer disposal and feed binding. */
internal class FeedVideoPositionHandoff {
    private val pending = linkedMapOf<ViewerMediaKey, Long>()
    private val receivers = mutableMapOf<ViewerMediaKey, MutableList<(Long) -> Unit>>()

    fun publish(key: ViewerMediaKey, positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        val active = receivers[key]
        if (!active.isNullOrEmpty()) {
            active.toList().forEach { it(position) }
        } else {
            pending.remove(key)
            pending[key] = position
            if (pending.size > MAX_PENDING_POSITIONS) pending.remove(pending.keys.first())
        }
    }

    fun bind(key: ViewerMediaKey, seek: (Long) -> Unit): Closeable {
        receivers.getOrPut(key) { mutableListOf() }.add(seek)
        pending.remove(key)?.let(seek)
        return Closeable {
            receivers[key]?.let { active ->
                active.remove(seek)
                if (active.isEmpty()) receivers.remove(key)
            }
        }
    }

    private companion object {
        const val MAX_PENDING_POSITIONS = 32
    }
}
