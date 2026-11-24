package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

class SharedFlights<K : Any, V> {
    private class Flight<V>(val request: Deferred<V>, var waiters: Int)

    private val lock = Any()
    private val flights = mutableMapOf<K, Flight<V>>()

    val inFlightCount: Int get() = synchronized(lock) { flights.size }

    suspend fun share(key: K, fetch: suspend () -> V): V {
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
