package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorFollowsRepositoryTest {
    private val creator = CreatorProfile(SourceKey.PIXIV, "Artist", "42", uploadsQuery = "user:42")

    @Test fun `identity ignores display changes and remains source scoped`() = runTest {
        val settings = InMemorySettingsRepository()
        val repository = CreatorFollowsRepository(settings)
        repository.follow(creator, emptyList())
        repository.follow(creator.copy(displayName = "Renamed"), emptyList())
        assertEquals(1, repository.observe().first().size)
        assertNotEquals(creator.followKey(), creator.copy(source = SourceKey.IWARA).followKey())
        val oldMembership = repository.observe().first().single()
        repository.unfollow(creator)
        repository.follow(creator, emptyList())
        repository.recordCheck(oldMembership, emptyList(), 123L)
        assertEquals(null, repository.observe().first().single().checkedAtEpochMs)
    }

    @Test fun `check reports new ids and an accepted visit clears them`() = runTest {
        val repository = CreatorFollowsRepository(InMemorySettingsRepository())
        fun post(id: String) = requireNotNull(com.theoriacodex.data.storage.PostStorageCodec.decode(
            com.theoriacodex.data.storage.PostStorageRecord(source = "PIXIV", sourcePostId = id)))
        repository.follow(creator, listOf(post("old")))
        val follow = repository.observe().first().single()
        repository.recordCheck(follow, listOf(post("new"), post("old"), post("new")), 42L)
        assertEquals(1, repository.observe().first().single().newPostCount)
        repository.recordVisit(creator, listOf(post("new"), post("old")))
        assertEquals(0, repository.observe().first().single().newPostCount)
        repository.unfollow(creator)
        repository.recordCheck(follow, listOf(post("later")), 43L)
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test fun `follow records round trip and old settings decode empty`() {
        val gson = Gson()
        val follow = FollowedCreator(creator, "membership", listOf("seen"), listOf("seen", "new"), 42L)
        val record = LegacySettingsStoreRecord.fromDomain(AppSettings(followedCreators = listOf(follow)))
        val decoded = gson.fromJson(gson.toJson(record), LegacySettingsStoreRecord::class.java).toDomain()
        assertEquals(follow, decoded.followedCreators.single())
        assertEquals(1, decoded.followedCreators.single().newPostCount)
        assertTrue(gson.fromJson("{}", LegacySettingsStoreRecord::class.java).toDomain().followedCreators.isEmpty())
    }
}
