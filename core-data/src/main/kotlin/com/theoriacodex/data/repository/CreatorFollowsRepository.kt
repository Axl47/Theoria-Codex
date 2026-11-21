package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Canonical provider identity; display names are never identity. */
fun CreatorProfile.followKey(): String = "${source.id}:${profileId?.takeIf(String::isNotBlank)
    ?: uploadsQuery?.takeIf(String::isNotBlank) ?: profileUrl.orEmpty()}"

data class FollowedCreator(
    val creator: CreatorProfile,
    val membershipId: String,
    val seenPostIds: List<String> = emptyList(),
    val latestPostIds: List<String> = emptyList(),
    val checkedAtEpochMs: Long? = null,
) {
    val marks: NewPostMarks get() = NewPostMarks(seenPostIds, latestPostIds, checkedAtEpochMs)
    val newPostCount: Int get() = marks.newPostCount
    val newPostIds: List<PostId> get() = marks.newPostIds.map { PostId(creator.source, it) }

    fun withMarks(marks: NewPostMarks) =
        copy(seenPostIds = marks.seenPostIds, latestPostIds = marks.latestPostIds, checkedAtEpochMs = marks.checkedAtEpochMs)
}

/** Local membership only. Refreshes cannot resurrect a removed or replaced follow. */
class CreatorFollowsRepository(private val settings: SettingsRepository) {
    fun observe() = settings.observeSettings().map { it.followedCreators }.distinctUntilChanged()

    suspend fun follow(creator: CreatorProfile, visiblePosts: List<Post>) {
        require(!creator.uploadsQuery.isNullOrBlank()) { "Creator has no uploads query" }
        settings.updateSettings { current ->
            if (current.followedCreators.any { it.creator.followKey() == creator.followKey() }) current
            else {
                require(current.followedCreators.size < MAX_FOLLOWED_CREATORS) { "Follow limit reached (200 creators)" }
                val ids = visiblePosts.filter { it.id.source == creator.source }.map { it.id.sourcePostId }.distinct().take(MAX_FOLLOW_POST_IDS)
                current.copy(followedCreators = current.followedCreators + FollowedCreator(creator, UUID.randomUUID().toString(), ids, ids))
            }
        }
    }

    suspend fun unfollow(creator: CreatorProfile) {
        settings.updateSettings { it.copy(followedCreators = it.followedCreators.filterNot { follow ->
            follow.creator.followKey() == creator.followKey()
        }) }
    }

    suspend fun recordCheck(follow: FollowedCreator, posts: List<Post>, checkedAt: Long) {
        val ids = posts.filter { it.id.source == follow.creator.source }.map { it.id.sourcePostId }
        settings.updateSettings { current -> current.copy(followedCreators = current.followedCreators.map {
            if (it.membershipId == follow.membershipId) it.withMarks(it.marks.checked(ids, checkedAt)) else it
        }) }
    }

    /** Opening the Followed feed shows every creator's latest posts, so none of them stays new. */
    suspend fun markLatestSeen() {
        settings.updateSettings { current ->
            if (current.followedCreators.none { it.newPostCount > 0 }) current
            else current.copy(followedCreators = current.followedCreators.map { it.withMarks(it.marks.seen()) })
        }
    }

    suspend fun recordVisit(creator: CreatorProfile, posts: List<Post>) {
        val ids = posts.filter { it.id.source == creator.source }.map { it.id.sourcePostId }.distinct()
        settings.updateSettings { current -> current.copy(followedCreators = current.followedCreators.map {
            if (it.creator.followKey() == creator.followKey()) it.copy(
                seenPostIds = (ids + it.seenPostIds).distinct().take(MAX_FOLLOW_POST_IDS),
            ) else it
        }) }
    }
}

const val MAX_FOLLOWED_CREATORS = 200
private const val MAX_FOLLOW_POST_IDS = MAX_NEW_POST_MARK_IDS
