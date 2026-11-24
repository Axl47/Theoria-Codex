package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryRepositoriesTest {
    @Test
    fun `cache repository tracks thumbnail and full counts`() = runTest {
        val repo = InMemoryCacheRepository()

        repo.cacheThumbnail(samplePost("1"))
        repo.cacheFull(samplePost("2"))

        val snapshot = repo.observeSnapshot().first()

        assertEquals(1, snapshot.thumbnailCount)
        assertEquals(1, snapshot.fullImageCount)
    }

    @Test
    fun `settings repository normalizes source weights`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        repo.updateSettings { it.copy(runtime = it.runtime.copy(sourceWeights = mapOf(SourceKey.PIXIV to 3.0, SourceKey.GELBOORU to 1.0))) }
        repo.setResolveUnknownAnimatedDurations(true)
        repo.setInvertMultiImageScrollDirection(true)
        repo.setActiveProfile("profile-alt")

        val settings = repo.observeSettings().first()
        val pixivWeight = settings.runtime.sourceWeights.getValue(SourceKey.PIXIV)
        val gelbooruWeight = settings.runtime.sourceWeights.getValue(SourceKey.GELBOORU)

        assertEquals(1.0, pixivWeight + gelbooruWeight, 0.0001)
        assertTrue(pixivWeight > gelbooruWeight)
        assertTrue(settings.contentFilters.resolveUnknownAnimatedDurations)
        assertTrue(settings.viewer.invertMultiImageScrollDirection)
        assertEquals("profile-alt", settings.activeProfileId)
    }

    @Test
    fun `settings repository supports dynamic recommendation profiles`() = runTest {
        val repo = InMemorySettingsRepository()

        val created = repo.addRecommendationProfile("Sketching")
        assertTrue(
            repo.addForYouBlacklistEntry(
                profileId = created.profileId,
                source = SourceKey.PIXIV,
                tags = listOf("artist", "portrait"),
            )
        )
        assertTrue(
            repo.addFavoriteTag(
                profileId = created.profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("Blue Hair"),
            )
        )
        var settings = repo.observeSettings().first()

        assertTrue(settings.recommendationProfiles.any { it.profileId == created.profileId && it.name == "Sketching" })
        assertEquals(created.profileId, settings.activeProfileId)
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile[created.profileId].orEmpty().map { it.tags.single() })

        assertTrue(repo.removeRecommendationProfile(created.profileId))
        settings = repo.observeSettings().first()
        assertTrue(settings.recommendationProfiles.none { it.profileId == created.profileId })
        assertTrue(settings.forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertTrue(settings.favoriteTagsByProfile[created.profileId].isNullOrEmpty())

        val removable = settings.recommendationProfiles.drop(1)
        removable.forEach { profile ->
            assertTrue(repo.removeRecommendationProfile(profile.profileId))
        }
        settings = repo.observeSettings().first()
        assertEquals(1, settings.recommendationProfiles.size)
        assertFalse(repo.removeRecommendationProfile(settings.recommendationProfiles.first().profileId))
    }

    @Test
    fun `settings repository deduplicates for you blacklist entries`() = runTest {
        val repo = InMemorySettingsRepository()
        val profileId = repo.observeSettings().first().activeProfileId

        assertTrue(
            repo.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tags = listOf("Night", "Cat"),
            )
        )
        assertFalse(
            repo.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tags = listOf("cat", "night"),
            )
        )

        var settings = repo.observeSettings().first()
        val entries = settings.forYouBlacklistByProfile[profileId].orEmpty()
        assertEquals(1, entries.size)
        assertEquals(listOf("cat", "night"), entries.first().tags)

        assertTrue(
            repo.removeForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tags = listOf("cat", "night"),
            )
        )
        settings = repo.observeSettings().first()
        assertTrue(settings.forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository deduplicates favorite tags per profile and source`() = runTest {
        val repo = InMemorySettingsRepository()
        val profileId = repo.observeSettings().first().activeProfileId

        assertTrue(
            repo.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("Blue Hair"),
            )
        )
        assertFalse(
            repo.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("blue_hair"),
            )
        )
        assertTrue(
            repo.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tags = listOf("Blue Hair"),
            )
        )

        var settings = repo.observeSettings().first()
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.GELBOORU }.map { it.tags.single() })
        assertEquals(listOf("Blue Hair"), settings.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.PIXIV }.map { it.tags.single() })

        assertTrue(
            repo.removeFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("blue_hair"),
            )
        )
        settings = repo.observeSettings().first()
        assertEquals(1, settings.favoriteTagsByProfile[profileId].orEmpty().size)
        assertEquals(SourceKey.PIXIV, settings.favoriteTagsByProfile[profileId].orEmpty().first().source)
    }

    @Test
    fun `likes repository toggles and isolates profiles`() = runTest {
        val repo = InMemoryLikesRepository()
        val post = samplePost("1")

        val added = repo.toggleLike(
            profileId = "profile-main",
            postId = post.id,
            tags = listOf("cloud", "cloud", "sky"),
        )

        assertTrue(added)
        assertTrue(post.id in repo.observeLikedPostIds("profile-main").first())
        assertEquals(1, repo.observeLikes("profile-main").first().size)
        assertTrue(repo.observeLikedPostIds("profile-alt").first().isEmpty())

        val removed = repo.toggleLike(
            profileId = "profile-main",
            postId = post.id,
            tags = listOf("ignored"),
        )

        assertFalse(removed)
        assertTrue(repo.observeLikedPostIds("profile-main").first().isEmpty())
    }

    @Test
    fun `ui restore repository stores tab scroll and viewer context`() = runTest {
        val repo = InMemoryUiRestoreRepository()
        val context = ViewerLaunchContext(
            queryHash = "hash-1",
            startIndex = 3,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 180,
        )

        repo.setLastTab("codex")
        repo.setSearchScrollState("hash-1", SearchScrollState(firstVisibleItemIndex = 2, firstVisibleItemOffsetPx = 120))
        repo.setViewerLaunchContext(context)

        assertEquals("codex", repo.getLastTab())
        assertEquals(2, repo.getSearchScrollState("hash-1")?.firstVisibleItemIndex)
        assertEquals(context, repo.observeViewerLaunchContext().first())
    }

    private fun samplePost(id: String, source: SourceKey = SourceKey.PIXIV): Post {
        return repositoryTestPost(id = id, source = source)
    }
}
