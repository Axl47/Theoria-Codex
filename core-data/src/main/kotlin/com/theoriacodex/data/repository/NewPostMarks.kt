package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NewPostMarks(
    @SerialName("seen") val seenPostIds: List<String> = emptyList(),
    @SerialName("latest") val latestPostIds: List<String> = emptyList(),
    @SerialName("checkedAt") val checkedAtEpochMs: Long? = null,
) {
    val newPostIds: List<String> get() = latestPostIds.filter { it !in seenPostIds }
    val newPostCount: Int get() = newPostIds.size

    fun checked(ids: List<String>, atEpochMs: Long): NewPostMarks {
        val latest = ids.distinct().take(MAX_NEW_POST_MARK_IDS)
        val baseline = checkedAtEpochMs == null && seenPostIds.isEmpty()
        return NewPostMarks(if (baseline) latest else seenPostIds, latest, atEpochMs)
    }

    fun seen(shown: (String) -> Boolean = { true }): NewPostMarks {
        val seen = seenPostIds.toSet()
        val current = latestPostIds.filter { it in seen || shown(it) }
        return copy(seenPostIds = (current + seenPostIds).distinct().take(MAX_NEW_POST_MARK_IDS))
    }
}

const val MAX_NEW_POST_MARK_IDS = 200

fun PostId.markKey(): String = "${source.id}:$sourcePostId"

@Serializable
data class SavedSearchNewPosts(
    @SerialName("attempts") val attempts: Map<String, Long> = emptyMap(),
    @SerialName("marks") val marks: Map<String, NewPostMarks> = emptyMap(),
)

const val NEW_POSTS_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L

fun newPostsCheckDue(attemptsEpochMs: List<Long?>, nowEpochMs: Long): Boolean =
    attemptsEpochMs.filterNotNull().maxOrNull()?.let { nowEpochMs - it >= NEW_POSTS_CHECK_INTERVAL_MS } ?: true
