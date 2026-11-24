package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.LikeTagRewriter
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.normalizeMatchToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun likeTagsWithStored(stored: Post, tags: List<String>): List<String> = (trainingTagsFor(stored) + tags).distinctBy(::normalizeMatchToken)

class LikeTagBackfill(
    private val rewriter: LikeTagRewriter,
    private val isDone: () -> Boolean,
    private val markDone: () -> Unit,
) {
    private val running = Mutex()

    suspend fun ensure() {
        running.withLock {
            if (isDone()) return
            runCatchingPreservingCancellation { rewriter.rewriteLikeTags(::likeTagsWithStored) }
                .onSuccess { markDone() }
                .onFailure { Diagnostics.report("for-you", "Like tags could not be filled in", it) }
        }
    }
}
