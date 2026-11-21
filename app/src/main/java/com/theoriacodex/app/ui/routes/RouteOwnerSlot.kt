package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.io.Closeable

/**
 * The shell's reference to one navigation-owned ViewModel, which the shell reaches but never owns.
 *
 * A destination publishes its ViewModel while composed. The slot empties itself when that
 * ViewModel is cleared, so the shell can keep reaching a still-live back-stack owner (a Viewer
 * paging the feed under it) but never one whose entry is gone. Publishing a replacement stops
 * watching the previous owner, so that owner's later clear cannot empty the slot.
 */
@Stable
internal class RouteOwnerSlot<Owner : ViewModel> {
    /** The published owner, observable from composition; null before publication and after its clear. */
    var current: Owner? by mutableStateOf(null)
        private set
    private var clearedRegistration: Closeable? = null

    fun publish(owner: Owner) {
        if (current === owner) return
        clearedRegistration?.close()
        current = owner
        val registration = owner.clearedSignal().register { if (current === owner) clear() }
        // An owner cleared before publication empties the slot at once, inside register.
        if (current === owner) clearedRegistration = registration else registration.close()
    }

    /** Empties the slot without touching the owner. */
    fun clear() {
        clearedRegistration?.close()
        clearedRegistration = null
        current = null
    }
}

/** Tells its listeners once that its ViewModel was cleared. Each ViewModel keeps one, as a keyed closeable. */
private class OwnerClearedSignal : Closeable {
    private val listeners = mutableListOf<() -> Unit>()
    private var cleared = false

    /** Calls [listener] when the ViewModel is cleared, or now if it already was; closing the result unregisters it. */
    fun register(listener: () -> Unit): Closeable {
        val alreadyCleared = synchronized(this) {
            if (!cleared) listeners += listener
            cleared
        }
        if (alreadyCleared) listener()
        return Closeable { synchronized(this) { listeners.removeAll { it === listener } } }
    }

    override fun close() {
        val pending = synchronized(this) {
            cleared = true
            listeners.toList().also { listeners.clear() }
        }
        pending.forEach { listener -> listener() }
    }
}

/**
 * The ViewModel's one cleared signal. A navigation entry can leave and re-enter composition without
 * its ViewModel being cleared, so republishing reuses the signal instead of adding another closeable.
 */
private fun ViewModel.clearedSignal(): OwnerClearedSignal = synchronized(this) {
    getCloseable(OWNER_CLEARED_SIGNAL_KEY) ?: OwnerClearedSignal().also { addCloseable(OWNER_CLEARED_SIGNAL_KEY, it) }
}

private const val OWNER_CLEARED_SIGNAL_KEY = "route-owner-cleared"
