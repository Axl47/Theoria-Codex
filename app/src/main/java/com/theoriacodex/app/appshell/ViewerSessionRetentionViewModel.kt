package com.theoriacodex.app.appshell

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel

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

    fun handoffTo(owner: ViewerViewModel, claimedSessionId: String?): Boolean {
        val current = mutableSession.value ?: return false
        if (current.sessionId != claimedSessionId) return false
        owner.replaceSession(current)
        mutableSession.value = null
        return true
    }
}
