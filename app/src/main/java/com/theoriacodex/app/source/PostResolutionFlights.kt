package com.theoriacodex.app.source

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * Shares one provider request per post between everything that resolves it, so the feed and the Viewer asking
 * for the same post at once send a single detail request. A request that starts after another finished goes to
 * the provider again, so callers that need fresh media (recovering a failed URL) keep their own request and do
 * not come through here. See [SharedFlights] for how a shared request ends.
 */
class PostResolutionFlights {
    private val flights = SharedFlights<PostId, Post?>()

    /** Runs [fetch] unless a request for [postId] is already in flight, then waits for that request's result. */
    suspend fun resolve(postId: PostId, fetch: suspend () -> Post?): Post? = flights.share(postId, fetch)
}
