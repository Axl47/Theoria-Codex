package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId

class InMemoryForYouVerdictRepository : ForYouVerdictRepository {
    private val stored = LinkedHashMap<Pair<String, PostId>, ForYouVerdict>()

    override suspend fun record(verdict: ForYouVerdict) {
        synchronized(stored) { stored[verdict.profileId to verdict.postId] = verdict }
    }

    override suspend fun remove(verdict: ForYouVerdict) {
        synchronized(stored) { stored.remove(verdict.profileId to verdict.postId, verdict) }
    }

    override suspend fun verdicts(profileId: String): List<ForYouVerdict> = synchronized(stored) {
        stored.values.filter { it.profileId == profileId }.sortedByDescending { it.decidedAtEpochMs }
    }

    override suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int): Set<PostId> =
        verdicts(profileId).filter { it.kind == ForYouVerdictKind.SKIPPED && it.decidedAtEpochMs in sinceEpochMs..untilEpochMs }
            .take(limit).mapTo(LinkedHashSet()) { it.postId }
}
