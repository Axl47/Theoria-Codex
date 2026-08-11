package com.theoriacodex.app.appshell

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel

/**
 * Retains a pending Viewer navigation payload while Android recreates the host Activity.
 *
 * This is deliberately Activity-scoped and process-local. A Viewer session contains resolved
 * posts and transient stream bindings, so it must not be promoted to the application container
 * or serialized as durable state. The destination consumes and clears this holder immediately;
 * [ViewerViewModel] is the only owner while the Viewer route is active.
 */
internal class ViewerSessionRetentionViewModel : ViewModel() {
    private val mutableSession = mutableStateOf<ViewerSession?>(null)

    val session: State<ViewerSession?> = mutableSession

    fun retain(session: ViewerSession) {
        mutableSession.value = session
    }

    fun update(transform: (ViewerSession) -> ViewerSession) {
        val current = mutableSession.value ?: return
        mutableSession.value = transform(current)
    }

    fun clear() {
        mutableSession.value = null
    }

    /**
     * Transfers only the process-local payload claimed when this route entry started.
     *
     * Navigation keeps an exiting destination composed briefly. Matching the claim identity stops
     * that old entry from consuming a newer payload retained for a rapid Viewer re-entry.
     */
    fun handoffTo(owner: ViewerViewModel, claimedSessionId: String?): Boolean {
        val current = mutableSession.value ?: return false
        if (current.sessionId != claimedSessionId) return false
        owner.replaceSession(current)
        mutableSession.value = null
        return true
    }
}
