package com.theoriacodex.app.ui.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RouteStateOwner<State : Any, Action : Any, Effect : Any> {
    val state: StateFlow<State>
    val effects: Flow<Effect>

    fun onAction(action: Action)
}

object AppRouteSavedStateKeys {
    const val HOME_TAB_ROUTE = "home_tab_route"
    const val PENDING_INCOMING_URI = "pending_incoming_uri"
    const val VIEWER_SESSION_ID = "viewer_session_id"
}
