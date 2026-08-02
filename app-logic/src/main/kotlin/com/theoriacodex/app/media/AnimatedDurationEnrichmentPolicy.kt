package com.theoriacodex.app.media

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.util.LinkedHashMap
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val negativeRetryAfterMs: Long = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_TTL_MS,
    private val negativeDecisionLimit: Int = ANIMATED_DURATION_ENRICHMENT_NEGATIVE_DECISION_LIMIT,
) {
    private val stateLock = Any()
    private val negativeRetryAtByPostId = LinkedHashMap<PostId, Long>(16, 0.75f, true)
    private var activeIdentity: SessionIdentity? = null
    private var requestedGeneration = 0L
    private var worker: Job? = null
    private var rerunRequested = false

    init {
        require(negativeRetryAfterMs > 0L) { "Negative retry delay must be positive" }
        require(negativeDecisionLimit > 0) { "Negative decision limit must be positive" }
    }

    fun request(identity: SessionIdentity) {
        if (currentIdentity() != identity) return
        synchronized(stateLock) {
            if (activeIdentity != identity) {
                activeIdentity = identity
                negativeRetryAtByPostId.clear()
            }
            requestedGeneration += 1L
            if (worker?.isActive == true) {
                rerunRequested = true
            } else {
                startWorkerLocked(identity, requestedGeneration)
            }
        }
    }

    private fun startWorkerLocked(identity: SessionIdentity, generation: Long) {
        rerunRequested = false
        worker = scope.launch {
            try {
                drain(identity, generation)
            } finally {
                val next = synchronized(stateLock) {
                    worker = null
                    activeIdentity.takeIf { active -> rerunRequested && active == currentIdentity() }
                        ?.let { active -> active to requestedGeneration }
                        ?.also { rerunRequested = false }
                }
                if (next != null) synchronized(stateLock) {
                    if (worker?.isActive != true && activeIdentity == next.first) {
                        startWorkerLocked(next.first, next.second)
                    }
                }
            }
        }
    }

    private suspend fun drain(identity: SessionIdentity, generation: Long) {
        val excluded = synchronized(stateLock) {
            if (activeIdentity != identity) return
            unexpiredNegativePostIds(clock())
        }
        val candidates = animatedDurationEnrichmentCandidates(
            posts = currentPosts(),
            excludedPostIds = excluded,
            limit = Int.MAX_VALUE,
        )
        candidates.chunked(ANIMATED_DURATION_ENRICHMENT_BATCH_SIZE).forEach { batch ->
            if (currentIdentity() != identity) return
            val outcomes = coroutineScope {
                batch.map { post -> async { post.id to enricher.enrich(post) } }
                    .awaitAll()
            }
            synchronized(stateLock) {
                if (activeIdentity == identity) {
                    val retryAt = clock() + negativeRetryAfterMs
                    outcomes.filter { (_, enrichment) -> enrichment == null }
                        .forEach { (postId, _) -> putNegativeDecision(postId, retryAt) }
                }
            }
            if (currentIdentity() != identity) return
            val enrichments = outcomes.mapNotNull { (_, enrichment) -> enrichment }
            if (enrichments.isNotEmpty()) applyEnrichments(identity, enrichments)
            if (synchronized(stateLock) { requestedGeneration != generation }) return
        }
    }

    private fun unexpiredNegativePostIds(now: Long): Set<PostId> {
        negativeRetryAtByPostId.entries.removeAll { (_, retryAt) -> now >= retryAt }
        return negativeRetryAtByPostId.keys.toSet()
    }

    private fun putNegativeDecision(postId: PostId, retryAt: Long) {
        negativeRetryAtByPostId.remove(postId)
        negativeRetryAtByPostId[postId] = retryAt
        while (negativeRetryAtByPostId.size > negativeDecisionLimit) {
            negativeRetryAtByPostId.remove(negativeRetryAtByPostId.keys.first())
        }
    }
}

const val ANIMATED_DURATION_ENRICHMENT_BATCH_SIZE = 8
const val ANIMATED_DURATION_ENRICHMENT_NEGATIVE_DECISION_LIMIT = 128
const val ANIMATED_DURATION_ENRICHMENT_NEGATIVE_TTL_MS = 5L * 60L * 1_000L
