package com.theoriacodex.app.media

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

data class AnimatedDurationEnrichment(
    val postId: PostId,
    val durationMs: Long,
)

interface AnimatedDurationEnricher {
    suspend fun enrich(post: Post): AnimatedDurationEnrichment?
}

object NoOpAnimatedDurationEnricher : AnimatedDurationEnricher {
    override suspend fun enrich(post: Post): AnimatedDurationEnrichment? = null
}

fun animatedDurationEnrichmentCandidates(
    posts: List<Post>,
    excludedPostIds: Set<PostId> = emptySet(),
    limit: Int = ANIMATED_DURATION_ENRICHMENT_BATCH_SIZE,
): List<Post> {
    if (limit <= 0) return emptyList()
    return posts.asSequence()
        .filter(::isAnimatedPost)
        .filter { post -> animatedDurationMs(post) == null }
        .distinctBy(Post::id)
        .filterNot { post -> post.id in excludedPostIds }
        .take(limit)
        .toList()
}

/** Route-owned drain lane; the application supplies identity and immutable publication hooks. */
class AnimatedDurationEnrichmentLane<SessionIdentity>(
    private val scope: CoroutineScope,
    private val enricher: AnimatedDurationEnricher,
    private val currentIdentity: () -> SessionIdentity?,
    private val currentPosts: () -> List<Post>,
    private val applyEnrichments: (SessionIdentity, List<AnimatedDurationEnrichment>) -> Unit,
) {
    private val stateLock = Any()
    private val attemptedPostIds = mutableSetOf<PostId>()
    private var activeIdentity: SessionIdentity? = null
    private var worker: Job? = null
    private var rerunRequested = false

    fun request(identity: SessionIdentity) {
        if (currentIdentity() != identity) return
        synchronized(stateLock) {
            if (activeIdentity != identity) {
                activeIdentity = identity
                attemptedPostIds.clear()
            }
            if (worker?.isActive == true) rerunRequested = true else startWorkerLocked(identity)
        }
    }

    private fun startWorkerLocked(identity: SessionIdentity) {
        rerunRequested = false
        worker = scope.launch {
            try {
                drain(identity)
            } finally {
                val nextIdentity = synchronized(stateLock) {
                    worker = null
                    activeIdentity.takeIf { active -> rerunRequested && active == currentIdentity() }
                        ?.also { rerunRequested = false }
                }
                if (nextIdentity != null) synchronized(stateLock) {
                    if (worker?.isActive != true && activeIdentity == nextIdentity) {
                        startWorkerLocked(nextIdentity)
                    }
                }
            }
        }
    }

    private suspend fun drain(identity: SessionIdentity) {
        while (currentIdentity() == identity) {
            val candidates = synchronized(stateLock) {
                if (activeIdentity != identity) return
                animatedDurationEnrichmentCandidates(currentPosts(), attemptedPostIds)
                    .also { batch -> attemptedPostIds += batch.map(Post::id) }
            }
            if (candidates.isEmpty()) return
            val enrichments = coroutineScope {
                candidates.map { post -> async { enricher.enrich(post) } }
                    .awaitAll()
                    .filterNotNull()
            }
            if (currentIdentity() != identity) return
            if (enrichments.isNotEmpty()) applyEnrichments(identity, enrichments)
        }
    }
}

const val ANIMATED_DURATION_ENRICHMENT_BATCH_SIZE = 8
