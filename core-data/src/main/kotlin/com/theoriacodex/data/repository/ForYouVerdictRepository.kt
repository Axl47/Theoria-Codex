package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId

enum class ForYouVerdictKind { LIKED, SAVED, SKIPPED, HID }

data class ForYouVerdict(
    val profileId: String,
    val postId: PostId,
    val kind: ForYouVerdictKind,
    val decidedAtEpochMs: Long,
    val previewUrl: String?,
)

interface ForYouVerdictRepository {
    suspend fun record(verdict: ForYouVerdict)

    suspend fun remove(verdict: ForYouVerdict)

    suspend fun verdicts(profileId: String): List<ForYouVerdict>

    suspend fun skippedBetween(profileId: String, sinceEpochMs: Long, untilEpochMs: Long, limit: Int): Set<PostId>
}

const val FOR_YOU_VERDICT_LIMIT = 20_000
