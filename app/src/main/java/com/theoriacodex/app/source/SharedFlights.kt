package com.theoriacodex.app.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * Shares one request per key between everything that asks for it at once. Only requests that overlap are
 * shared; one that starts after another finished runs again, so callers that need a fresh answer keep their
 * own request and do not come through here.
 *
 * The request outlives any one caller: it is cancelled only when every caller waiting on it has left.
 */
class SharedFlights<K : Any, V> {
    private class Flight<V>(val request: Deferred<V>, var waiters: Int)

    private val lock = Any()
    private val flights = mutableMapOf<K, Flight<V>>()

    /** Runs [fetch] unless a request for [key] is already in flight, then waits for that request's result. */
    suspend fun share(key: K, fetch: suspend () -> V): V {
        // The request runs where the first caller's dispatcher does, but under its own job, so that caller
        // leaving does not cancel the others.
        val context = currentCoroutineContext().minusKey(Job)
        val flight = synchronized(lock) {
            flights[key]?.also { it.waiters += 1 } ?: startFlight(key, context, fetch)
        }
        try {
            return flight.request.await()
        } finally {
            synchronized(lock) {
                flight.waiters -= 1
                if (flight.waiters == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.request.cancel()
                }
            }
        }
    }

    private fun startFlight(key: K, context: CoroutineContext, fetch: suspend () -> V): Flight<V> {
        val scope = CoroutineScope(context + SupervisorJob())
        val request = scope.async(start = CoroutineStart.LAZY) { fetch() }
        val flight = Flight(request, waiters = 1)
        flights[key] = flight
        request.invokeOnCompletion {
            synchronized(lock) { if (flights[key] === flight) flights.remove(key) }
            scope.coroutineContext.job.cancel()
        }
        request.start()
        return flight
    }
}
