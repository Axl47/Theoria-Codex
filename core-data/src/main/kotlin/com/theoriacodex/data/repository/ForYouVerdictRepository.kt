package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId

/** What a swipe on a For You card said about its post. */
enum class ForYouVerdictKind { LIKED, SAVED, SKIPPED, HID }

/**
 * A profile's latest swipe on a For You card, kept as a label for a future taste model. [previewUrl] is the still
 * preview the card showed, so the picture can be fetched again while it stays up; the post id resolves it otherwise.
 */
data class ForYouVerdict(
    val profileId: String,
    val postId: PostId,
    val kind: ForYouVerdictKind,
    val decidedAtEpochMs: Long,
    val previewUrl: String?,
)

/**
 * The log of For You swipes, one per profile and post (a later swipe replaces an earlier one). Only the newest
 * [FOR_YOU_VERDICT_LIMIT] per profile are kept. A removed profile's log goes with its library. The log is local: it is
 * not part of profile backups.
 */
interface ForYouVerdictRepository {
    suspend fun record(verdict: ForYouVerdict)

    /** Forgets [verdict], as Undo does, unless a later swipe on its post replaced it. */
    suspend fun remove(verdict: ForYouVerdict)

    /** The profile's verdicts, newest first. */
    suspend fun verdicts(profileId: String): List<ForYouVerdict>
}

/**
 * Per profile. A taste model learns from a few thousand labels and recent taste matters most, while 20,000 rows of
 * ids, a timestamp and a preview address stay around 5 MB; at a few hundred swipes a day that is months of history.
 */
const val FOR_YOU_VERDICT_LIMIT = 20_000
