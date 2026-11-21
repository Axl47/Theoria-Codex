package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
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

    @Test fun `restore puts the removed record back in place unless the creator was followed again`() = runTest {
        val repository = CreatorFollowsRepository(InMemorySettingsRepository())
        val other = creator.copy(profileId = "7", uploadsQuery = "user:7")
        repository.follow(creator, emptyList())
        repository.follow(other, emptyList())
        val before = repository.observe().first()
        val removed = requireNotNull(repository.unfollow(creator))
        assertEquals(0, removed.index)
        assertEquals(null, repository.unfollow(creator))
        assertEquals(null, repository.seenPostIds(creator))
        repository.restore(removed)
        assertEquals(before, repository.observe().first())
        assertEquals(emptySet<String>(), repository.seenPostIds(creator))

        repository.unfollow(creator)
        repository.follow(creator, emptyList())
        val refollowed = repository.observe().first().last()
        repository.restore(removed)
        assertEquals(listOf(before[1], refollowed), repository.observe().first())
    }

    @Test fun `follow records round trip and old settings decode empty`() {
        val follow = FollowedCreator(creator, "membership", listOf("seen"), listOf("seen", "new"), 42L)
        val record = SettingsStoreRecord.fromDomain(AppSettings(followedCreators = listOf(follow)))
        val decoded = DurableJson.decodeFromString<SettingsStoreRecord>(DurableJson.encodeToString(record)).toDomain()
        assertEquals(follow, decoded.followedCreators.single())
        assertEquals(1, decoded.followedCreators.single().newPostCount)
        assertTrue(DurableJson.decodeFromString<SettingsStoreRecord>("{}").toDomain().followedCreators.isEmpty())
    }
}
