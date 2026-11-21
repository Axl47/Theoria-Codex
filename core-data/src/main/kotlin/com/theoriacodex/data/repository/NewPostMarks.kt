package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A followed feed's first page as last checked ([latestPostIds]) and the posts already shown
 * ([seenPostIds]); what is latest but unseen counts as new. Followed creators and saved searches
 * both keep these, entirely on the device.
 */
@Serializable
data class NewPostMarks(
    @SerialName("seen") val seenPostIds: List<String> = emptyList(),
    @SerialName("latest") val latestPostIds: List<String> = emptyList(),
    @SerialName("checkedAt") val checkedAtEpochMs: Long? = null,
) {
    val newPostIds: List<String> get() = latestPostIds.filter { it !in seenPostIds }
    val newPostCount: Int get() = newPostIds.size

    /** Records a check's first-page [ids]. The first check with nothing seen yet only sets the baseline. */
    fun checked(ids: List<String>, atEpochMs: Long): NewPostMarks {
        val latest = ids.distinct().take(MAX_NEW_POST_MARK_IDS)
        val baseline = checkedAtEpochMs == null && seenPostIds.isEmpty()
        return NewPostMarks(if (baseline) latest else seenPostIds, latest, atEpochMs)
    }

    /** Everything last checked has now been shown. */
    fun seen(): NewPostMarks = copy(seenPostIds = (latestPostIds + seenPostIds).distinct().take(MAX_NEW_POST_MARK_IDS))
}

const val MAX_NEW_POST_MARK_IDS = 200

/** How a post spanning sources is kept in marks that are not scoped to one source. */
fun PostId.markKey(): String = "${source.id}:$sourcePostId"

/**
 * Saved searches' marks by saved-search ID, and when the app last tried checking them, so a
 * failing check waits out the interval even across restarts.
 */
@Serializable
data class SavedSearchNewPosts(
    @SerialName("attemptedAt") val attemptedAtEpochMs: Long? = null,
    @SerialName("marks") val marks: Map<String, NewPostMarks> = emptyMap(),
)

/** How long a check stays fresh before opening the app checks again. */
const val NEW_POSTS_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L

/** Whether the newest of [attemptsEpochMs] (checks and automatic attempts, null for never) is older than the interval. */
fun newPostsCheckDue(attemptsEpochMs: List<Long?>, nowEpochMs: Long): Boolean =
    attemptsEpochMs.filterNotNull().maxOrNull()?.let { nowEpochMs - it >= NEW_POSTS_CHECK_INTERVAL_MS } ?: true
