package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

fun CreatorProfile.followKey(): String = "${source.id}:${profileId?.takeIf(String::isNotBlank)
    ?: uploadsQuery?.takeIf(String::isNotBlank) ?: profileUrl.orEmpty()}"

data class FollowedCreator(
    val creator: CreatorProfile,
    val membershipId: String,
    val seenPostIds: List<String> = emptyList(),
    val latestPostIds: List<String> = emptyList(),
    val checkedAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null,
) {
    val marks: NewPostMarks get() = NewPostMarks(seenPostIds, latestPostIds, checkedAtEpochMs)
    val newPostCount: Int get() = marks.newPostCount
    val newPostIds: List<PostId> get() = marks.newPostIds.map { PostId(creator.source, it) }

    fun withMarks(marks: NewPostMarks) =
        copy(seenPostIds = marks.seenPostIds, latestPostIds = marks.latestPostIds, checkedAtEpochMs = marks.checkedAtEpochMs)
}

data class RemovedFollow(val follow: FollowedCreator, val index: Int)

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

    suspend fun unfollow(creator: CreatorProfile): RemovedFollow? {
        var removed: RemovedFollow? = null
        settings.updateSettings { current ->
            val index = current.followedCreators.indexOfFirst { it.creator.followKey() == creator.followKey() }
            if (index < 0) current
            else {
                removed = RemovedFollow(current.followedCreators[index], index)
                current.copy(followedCreators = current.followedCreators.filterIndexed { i, _ -> i != index })
            }
        }
        return removed
    }

    suspend fun restore(removed: RemovedFollow) {
        settings.updateSettings { current ->
            val follows = current.followedCreators
            if (follows.size >= MAX_FOLLOWED_CREATORS || follows.any { it.creator.followKey() == removed.follow.creator.followKey() }) current
            else current.copy(followedCreators = follows.toMutableList().apply { add(removed.index.coerceIn(0, size), removed.follow) })
        }
    }

    suspend fun seenPostIds(creator: CreatorProfile): Set<String>? =
        observe().first().firstOrNull { it.creator.followKey() == creator.followKey() }?.seenPostIds?.toSet()

    suspend fun claimChecks(membershipIds: Set<String>, at: Long, due: (FollowedCreator) -> Boolean): List<FollowedCreator> {
        var claimed = emptyList<FollowedCreator>()
        settings.updateSettings { current ->
            claimed = current.followedCreators.filter { it.membershipId in membershipIds && due(it) }
            val ids = claimed.mapTo(mutableSetOf()) { it.membershipId }
            if (ids.isEmpty()) current
            else current.copy(followedCreators = current.followedCreators.map { if (it.membershipId in ids) it.copy(attemptedAtEpochMs = at) else it })
        }
        return claimed
    }

    suspend fun recordChecks(results: Map<String, List<Post>?>, at: Long) {
        if (results.isEmpty()) return
        settings.updateSettings { current -> current.copy(followedCreators = current.followedCreators.map { follow ->
            if (!results.containsKey(follow.membershipId)) return@map follow
            val posts = results[follow.membershipId] ?: return@map follow.copy(attemptedAtEpochMs = at)
            val ids = posts.filter { it.id.source == follow.creator.source }.map { it.id.sourcePostId }
            follow.withMarks(follow.marks.checked(ids, at))
        }) }
    }

    suspend fun markSeen(shown: Set<String>) {
        settings.updateSettings { current ->
            if (current.followedCreators.none { follow -> follow.newPostIds.any { it.markKey() in shown } }) current
            else current.copy(followedCreators = current.followedCreators.map { follow ->
                follow.withMarks(follow.marks.seen { PostId(follow.creator.source, it).markKey() in shown })
            })
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
