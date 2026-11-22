package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId

/** The verdict log in memory: one verdict per profile and post, newest first, without the cap. */
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
}
