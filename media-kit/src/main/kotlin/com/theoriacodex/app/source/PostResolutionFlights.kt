package com.theoriacodex.app.source

import com.theoriacodex.domain.coroutines.SharedFlights
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

class PostResolutionFlights {
    private val flights = SharedFlights<PostId, Post?>()

    suspend fun resolve(postId: PostId, fetch: suspend () -> Post?): Post? = flights.share(postId, fetch)
}
