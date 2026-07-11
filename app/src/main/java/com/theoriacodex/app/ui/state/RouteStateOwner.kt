package com.theoriacodex.app.ui.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract shared by navigation-scoped feature owners.
 *
 * Implementations publish one immutable state stream and accept typed user intent. One-shot work
 * is delivered through a buffered Channel exposed as [effects], never encoded as sticky state.
 * Route owners may persist compact reconstruction inputs in SavedStateHandle, but large result
 * lists, Android handles, players, decoders, launchers, and repository/service instances stay out
 * of saved state.
 *
 * The application container supplies factories and long-lived engines. It must never retain a
 * route owner or ViewModel instance.
 */
internal interface RouteStateOwner<State : Any, Action : Any, Effect : Any> {
    val state: StateFlow<State>
    val effects: Flow<Effect>

    fun onAction(action: Action)
}

/**
 * Saved-state keys that cross the app-shell/navigation boundary.
 *
 * Feature-private keys remain beside their route owner. Keeping the shared list intentionally
 * small makes process restoration policy visible instead of turning SavedStateHandle into a
 * second persistence store.
 */
internal object AppRouteSavedStateKeys {
    const val HOME_TAB_ROUTE = "home_tab_route"
    const val PENDING_INCOMING_URI = "pending_incoming_uri"
    const val VIEWER_SESSION_ID = "viewer_session_id"
}
