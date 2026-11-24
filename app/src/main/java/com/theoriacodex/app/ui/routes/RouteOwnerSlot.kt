package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.io.Closeable

@Stable
internal class RouteOwnerSlot<Owner : ViewModel> {
    var current: Owner? by mutableStateOf(null)
        private set
    private var clearedRegistration: Closeable? = null

    fun publish(owner: Owner) {
        if (current === owner) return
        clearedRegistration?.close()
        current = owner
        val registration = owner.clearedSignal().register { if (current === owner) clear() }
        if (current === owner) clearedRegistration = registration else registration.close()
    }

    fun withdraw(owner: Owner) {
        if (current === owner) clear()
    }

    fun clear() {
        clearedRegistration?.close()
        clearedRegistration = null
        current = null
    }
}

private class OwnerClearedSignal : Closeable {
    private val listeners = mutableListOf<() -> Unit>()
    private var cleared = false

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

private fun ViewModel.clearedSignal(): OwnerClearedSignal = synchronized(this) {
    getCloseable(OWNER_CLEARED_SIGNAL_KEY) ?: OwnerClearedSignal().also { addCloseable(OWNER_CLEARED_SIGNAL_KEY, it) }
}

private const val OWNER_CLEARED_SIGNAL_KEY = "route-owner-cleared"
