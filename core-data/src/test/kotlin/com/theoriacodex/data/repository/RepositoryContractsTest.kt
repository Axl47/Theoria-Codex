package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryContractTest {
    @Test
    fun `legacy Recents viewer identities restore their typed section`() {
        assertEquals(
            RecentPostSection.CODEX,
            decodeRestoredRecentsSection(null, ViewerStreamSource.RECENTS, "recents:codex"),
        )
        assertEquals(
            RecentPostSection.WATCHED,
            decodeRestoredRecentsSection(null, ViewerStreamSource.RECENTS, "recents:watched"),
        )
        assertNull(decodeRestoredRecentsSection(null, ViewerStreamSource.SEARCH, "recents:codex"))
    }

    @Test
    fun `settings starts with a complete normalized source catalog`() = runTest {
        val settings = createSettingsRepository().observeSettings().first()

        assertEquals(SourceKey.builtIns.toSet(), settings.runtime.enabledSources)
        assertEquals(SourceKey.builtIns.toSet(), settings.runtime.sourceWeights.keys)
        assertEquals(1.0, settings.runtime.sourceWeights.values.sum(), 0.0000001)
        assertTrue(settings.runtime.sourceWeights.values.all { weight -> weight.isFinite() && weight >= 0.0 })
        assertEquals(1.0, SourceRuntimeSettings().sourceWeights.values.sum(), 0.0000001)
    }

    @Test
    fun `settings repairs invalid and all-zero source weights without NaN`() = runTest {
        val repository = createSettingsRepository()
        val enabled = setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.NHENTAI)
        repository.setEnabledSources(enabled)
        repository.updateSettings { it.copy(runtime = it.runtime.copy(sourceWeights = mapOf(
            SourceKey.PIXIV to Double.NaN,
            SourceKey.GELBOORU to Double.POSITIVE_INFINITY,
            SourceKey.NHENTAI to -1.0,
        ))) }

        var weights = repository.observeSettings().first().runtime.sourceWeights
        assertEquals(enabled, weights.keys)
        assertEquals(1.0, weights.values.sum(), 0.0000001)
        assertTrue(weights.values.all { weight -> weight.isFinite() && weight > 0.0 })
        assertTrue(weights.values.all { weight -> kotlin.math.abs(weight - (1.0 / 3.0)) < 0.0000001 })

        repository.updateSettings { it.copy(runtime = it.runtime.copy(sourceWeights = mapOf(
            SourceKey.PIXIV to Double.MAX_VALUE,
            SourceKey.GELBOORU to Double.MAX_VALUE,
            SourceKey.NHENTAI to 0.0,
        ))) }
        weights = repository.observeSettings().first().runtime.sourceWeights
        assertEquals(0.5, weights.getValue(SourceKey.PIXIV), 0.0000001)
        assertEquals(0.5, weights.getValue(SourceKey.GELBOORU), 0.0000001)
        assertEquals(0.0, weights.getValue(SourceKey.NHENTAI), 0.0000001)
        assertEquals(1.0, weights.values.sum(), 0.0000001)

        repository.setEnabledSources(emptySet())
        assertTrue(repository.observeSettings().first().runtime.sourceWeights.isEmpty())
    }

    @Test
    fun `settings applies one profile and source-aware tag policy`() = runTest {
        val repository = createSettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId

        assertTrue(repository.addFavoriteTag(" $profileId ", SourceKey.GELBOORU, listOf("Blue Hair")))
        assertFalse(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, listOf("blue_hair")))
        assertTrue(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("Night", "Cat")))
        assertFalse(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("cat", "night")))
        assertFalse(repository.addFavoriteTag("missing", SourceKey.PIXIV, listOf("cat")))
        assertFalse(repository.addForYouBlacklistEntry("missing", SourceKey.PIXIV, listOf("cat")))

        var settings = repository.observeSettings().first()
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile.getValue(profileId).map { it.tags.single() })
        assertEquals(
            listOf(listOf("cat", "night")),
            settings.forYouBlacklistByProfile.getValue(profileId).map(ForYouBlacklistEntry::tags),
        )

        assertTrue(repository.removeFavoriteTag(profileId, SourceKey.GELBOORU, listOf(" BLUE HAIR ")))
        assertTrue(repository.removeForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("NIGHT", "cat")))
        settings = repository.observeSettings().first()
        assertTrue(settings.favoriteTagsByProfile[profileId].isNullOrEmpty())
        assertTrue(settings.forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `a saved or group is one entry whatever the order of its tags`() = runTest {
        val repository = createSettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId

        assertTrue(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, listOf("Blue Hair", "red_hair", "blue_hair")))
        assertFalse(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, listOf("red_hair", "blue_hair")))
        assertTrue(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, listOf("blue_hair")))
        assertEquals(
            listOf(listOf("blue_hair", "red_hair"), listOf("blue_hair")),
            repository.observeSettings().first().favoriteTagsByProfile.getValue(profileId).map(FavoriteTagEntry::tags),
        )

        assertTrue(repository.removeFavoriteTag(profileId, SourceKey.GELBOORU, listOf("RED HAIR", "blue_hair")))
        assertEquals(
            listOf(listOf("blue_hair")),
            repository.observeSettings().first().favoriteTagsByProfile.getValue(profileId).map(FavoriteTagEntry::tags),
        )
    }

    @Test
    fun `a saved group can be named on saving and renamed, while a single tag stays unnamed`() = runTest {
        val repository = createSettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        suspend fun names() = repository.observeSettings().first().favoriteTagsByProfile.getValue(profileId).map(FavoriteTagEntry::name)

        assertTrue(repository.addFavoriteTag(profileId, SourceKey.PIXIV, listOf("sea", "sky"), " Blue\nthings "))
        assertTrue(repository.addFavoriteTag(profileId, SourceKey.PIXIV, listOf("cat"), "Cats"))
        assertEquals(listOf("Blue things", null), names())

        assertTrue(repository.renameFavoriteTag(profileId, SourceKey.PIXIV, listOf("sky", "sea"), "Outdoors"))
        assertFalse(repository.renameFavoriteTag(profileId, SourceKey.PIXIV, listOf("sky", "sea"), "Outdoors"))
        assertFalse(repository.renameFavoriteTag(profileId, SourceKey.PIXIV, listOf("cat"), "Cats"))
        assertEquals(listOf("Outdoors", null), names())

        assertTrue(repository.renameFavoriteTag(profileId, SourceKey.PIXIV, listOf("sea", "sky"), "  "))
        assertEquals(listOf(null, null), names())
    }

    @Test
    fun `renaming a recommendation profile keeps its identity and rejects unusable input`() = runTest {
        val repository = createSettingsRepository()
        val created = repository.addRecommendationProfile("Sketching")

        assertTrue(repository.renameRecommendationProfile(created.profileId, "  Painting  "))
        assertFalse(repository.renameRecommendationProfile(created.profileId, "   "))
        assertFalse(repository.renameRecommendationProfile("missing", "Other"))

        val settings = repository.observeSettings().first()
        assertEquals(created.profileId, settings.activeProfileId)
        assertEquals("Painting", settings.recommendationProfiles.single { it.profileId == created.profileId }.name)
    }

    @Test
    fun `recommendation profile mutation trims names and cleans profile-owned state`() = runTest {
        val repository = createSettingsRepository()
        val created = repository.addRecommendationProfile("  Sketching  ")
        assertEquals("Sketching", created.name)
        assertEquals(created.profileId, repository.observeSettings().first().activeProfileId)
        assertTrue(repository.addFavoriteTag(created.profileId, SourceKey.PIXIV, listOf("portrait")))
        assertTrue(repository.addForYouBlacklistEntry(created.profileId, SourceKey.PIXIV, listOf("draft")))

        assertTrue(repository.removeRecommendationProfile(" ${created.profileId} "))

        val settings = repository.observeSettings().first()
        assertTrue(settings.recommendationProfiles.none { profile -> profile.profileId == created.profileId })
        assertTrue(settings.favoriteTagsByProfile[created.profileId].isNullOrEmpty())
        assertTrue(settings.forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertFalse(repository.removeRecommendationProfile("missing"))
    }

    @Test
    fun `likes are profile-scoped normalized and toggling is reversible`() = runTest {
        val repository = createLikesRepository(clock = { 123L })
        val postId = repositoryTestPost(id = "post-1").id

        assertTrue(repository.toggleLike(" profile-main ", postId, listOf(" Tag ", "tag", "Other")))
        assertTrue(postId in repository.observeLikedPostIds("profile-main").first())
        assertTrue(repository.observeLikedPostIds("profile-alt").first().isEmpty())
        assertEquals(listOf("Tag", "Other"), repository.observeLikes("profile-main").first().single().tags)
        assertEquals(123L, repository.observeLikes("profile-main").first().single().likedAtEpochMs)

        assertFalse(repository.toggleLike("profile-main", postId, emptyList()))
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertFalse(repository.toggleLike("  ", postId, listOf("ignored")))
        assertTrue(repository.observeLikes("").first().isEmpty())

        assertTrue(repository.toggleLike("profile-main", postId, listOf("Again")))
        repository.clearLikes(" profile-main ")
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
    }

    @Test
    fun `ui restore starts without a tab and keeps the last one set`() = runTest {
        val repository = createUiRestoreRepository()

        assertEquals(null, repository.getLastTab())
        repository.setLastTab("settings")
        assertEquals("settings", repository.getLastTab())
    }

    @Test
    fun `ui restore keeps feed FAB contexts independent`() = runTest {
        val repository = createUiRestoreRepository()
        val search = FeedFabRestoreState(animatedOnly = true, hideWatched = true)
        val forYou = FeedFabRestoreState(sortMode = SortMode.RANDOM.name, durationMinBucket = 2)

        repository.setFeedFabRestoreState("search", search)
        repository.setFeedFabRestoreState("for-you", forYou)

        assertEquals(mapOf("search" to search, "for-you" to forYou), repository.getFeedFabRestoreStates())
    }

    private fun createSettingsRepository(): SettingsRepository {
        return InMemorySettingsRepository()
    }

    private fun createLikesRepository(clock: () -> Long): InMemoryLikesRepository {
        return InMemoryLikesRepository(clock = clock)
    }

    private fun createUiRestoreRepository(): UiRestoreRepository {
        return InMemoryUiRestoreRepository()
    }
}
