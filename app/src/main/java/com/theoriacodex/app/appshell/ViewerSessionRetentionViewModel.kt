package com.theoriacodex.app.appshell

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.theoriacodex.app.viewer.ViewerSession

/**
 * Retains the active Viewer handoff while Android recreates the host Activity.
 *
 * This is deliberately Activity-scoped and process-local. A Viewer session contains resolved
 * posts and transient stream bindings, so it must not be promoted to the application container
 * or serialized as durable state. Compose observes [session], while mutations stay behind this
 * holder so a newly created shell cannot accidentally replace the retained value with `null`.
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
}
