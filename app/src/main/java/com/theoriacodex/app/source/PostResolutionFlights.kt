package com.theoriacodex.app.source

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * Shares one provider request per post between everything that resolves it, so the feed and the Viewer asking
 * for the same post at once send a single detail request. Only requests that overlap are shared; a request that
 * starts after another finished goes to the provider again, so callers that need fresh media (recovering a
 * failed URL) keep their own request and do not come through here.
 *
 * The request outlives any one caller: it is cancelled only when every caller waiting on it has left.
 */
class PostResolutionFlights {
    private class Flight(val request: Deferred<Post?>, var waiters: Int)

    private val lock = Any()
    private val flights = mutableMapOf<PostId, Flight>()

    /** Runs [fetch] unless a request for [postId] is already in flight, then waits for that request's result. */
    suspend fun resolve(postId: PostId, fetch: suspend () -> Post?): Post? {
        // The request runs where the first caller's dispatcher does, but under its own job, so that caller
        // leaving does not cancel the others.
        val context = currentCoroutineContext().minusKey(Job)
        val flight = synchronized(lock) {
            flights[postId]?.also { it.waiters += 1 } ?: startFlight(postId, context, fetch)
        }
        try {
            return flight.request.await()
        } finally {
            synchronized(lock) {
                flight.waiters -= 1
                if (flight.waiters == 0) {
                    if (flights[postId] === flight) flights.remove(postId)
                    flight.request.cancel()
                }
            }
        }
    }

    private fun startFlight(postId: PostId, context: CoroutineContext, fetch: suspend () -> Post?): Flight {
        val scope = CoroutineScope(context + SupervisorJob())
        val request = scope.async(start = CoroutineStart.LAZY) { fetch() }
        val flight = Flight(request, waiters = 1)
        flights[postId] = flight
        request.invokeOnCompletion {
            synchronized(lock) { if (flights[postId] === flight) flights.remove(postId) }
            scope.coroutineContext.job.cancel()
        }
        request.start()
        return flight
    }
}
