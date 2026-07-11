package com.theoriacodex.app.ui.routes

/**
 * Preserves cross-route intent while a lazily composed destination has no active handle yet.
 *
 * Actions stay ordered. A failed flush leaves the failed action and everything after it queued so
 * a replacement destination owner can resume without silently dropping user intent.
 */
internal class PendingRouteActions<Action : Any> {
    private val actions = ArrayDeque<Action>()

    val size: Int
        get() = actions.size

    fun dispatchOrEnqueue(action: Action, dispatch: (Action) -> Boolean): Boolean {
        if (!dispatch(action)) actions.addLast(action)
        return true
    }

    fun flush(dispatch: (Action) -> Boolean): Int {
        var dispatched = 0
        while (actions.isNotEmpty()) {
            val next = actions.first()
            if (!dispatch(next)) break
            actions.removeFirst()
            dispatched += 1
        }
        return dispatched
    }
}
