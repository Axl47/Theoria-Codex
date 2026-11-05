package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryLikesRepository(
    private val clock: () -> Long = System::currentTimeMillis,
) : LikesRepository {
    private val mutex = Mutex()
    private val likesByProfile = MutableStateFlow<Map<String, Map<PostId, LikedPost>>>(emptyMap())

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        return likesByProfile.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)]
                .orEmpty()
                .values
                .sortedByDescending { it.likedAtEpochMs }
        }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        return likesByProfile.map { byProfile ->
            byProfile[RepositoryPolicies.normalizeProfileId(profileId)].orEmpty().keys
        }
    }

    suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        return mutex.withLock {
            val result = FixtureRepositoryPolicies.toggleLike(
                likesByProfile = likesByProfile.value,
                profileId = profileId,
                postId = postId,
                tags = tags,
                likedAtEpochMs = clock(),
            )
            likesByProfile.value = result.state
            result.value
        }
    }

    suspend fun removeLikes(profileId: String, postIds: Set<PostId>) {
        mutex.withLock {
            likesByProfile.value = likesByProfile.value + (profileId to likesByProfile.value[profileId].orEmpty().filterKeys {
                it !in postIds
            })
        }
    }

    suspend fun restoreLikes(likes: List<LikedPost>) {
        mutex.withLock {
            likes.groupBy(LikedPost::profileId).forEach { (profileId, rows) ->
                val current = likesByProfile.value[profileId].orEmpty()
                likesByProfile.value = likesByProfile.value + (profileId to (rows.associateBy(LikedPost::postId) + current))
            }
        }
    }

    suspend fun clearLikes(profileId: String) {
        mutex.withLock {
            likesByProfile.value = likesByProfile.value - RepositoryPolicies.normalizeProfileId(profileId)
        }
    }
}
