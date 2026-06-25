package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryRepositoriesTest {
    @Test
    fun `codex repository creates and tracks items`() = runTest {
        val repo = InMemoryCodexRepository()
        val codex = repo.createCodex("Favorites")
        repo.addItem(codex.codexId, samplePost("1"))

        val codices = repo.observeCodices().first()
        val items = repo.observeCodexItems(codex.codexId).first()

        assertEquals(1, codices.size)
        assertEquals("Favorites", codices.first().name)
        assertEquals(1, items.size)
    }

    @Test
    fun `codex repository deduplicates and sorts post hydration`() = runTest {
        val repo = InMemoryCodexRepository()
        val codex = repo.createCodex("Favorites")
        val pixiv = samplePost(id = "1", source = SourceKey.PIXIV)
        val gelbooru = samplePost(id = "2", source = SourceKey.GELBOORU)

        repo.addItem(codex.codexId, pixiv)
        repo.addItem(codex.codexId, gelbooru)
        repo.addItem(codex.codexId, pixiv)

        val items = repo.observeCodexItems(codex.codexId).first()
        val bySource = repo.observeCodexPosts(codex.codexId, CodexSortMode.BY_SOURCE).first()

        assertEquals(2, items.size)
        assertEquals(SourceKey.GELBOORU, bySource.first().id.source)
        assertNotNull(repo.getPost(pixiv.id))
    }

    @Test
    fun `codex repository ensures stable system codex`() = runTest {
        val repo = InMemoryCodexRepository()

        val first = repo.ensureCodex(codexId = "system_likes_codex", name = "Likes")
        val second = repo.ensureCodex(codexId = "system_likes_codex", name = "Likes")

        assertEquals(first.codexId, second.codexId)
        assertEquals("Likes", second.name)
        assertEquals(1, repo.observeCodices().first().size)
    }

    @Test
    fun `codex repository appends numeric suffix for duplicate names`() = runTest {
        val repo = InMemoryCodexRepository()

        val first = repo.createCodex("Favorites")
        val second = repo.createCodex("Favorites")
        val third = repo.ensureCodex(codexId = "manual", name = "Favorites")

        repo.renameCodex(second.codexId, "Favorites")

        val names = repo.observeCodices().first().associateBy({ it.codexId }, { it.name })
        assertEquals("Favorites", names[first.codexId])
        assertEquals("Favorites 2", names[second.codexId])
        assertEquals("Favorites 3", names[third.codexId])
    }

    @Test
    fun `codex repository supports reordering`() = runTest {
        val repo = InMemoryCodexRepository()
        val first = repo.createCodex("First")
        val second = repo.createCodex("Second")
        val third = repo.createCodex("Third")

        repo.reorderCodex(codexId = third.codexId, targetIndex = 0)

        val reordered = repo.observeCodices().first()
        assertEquals(listOf(third.codexId, first.codexId, second.codexId), reordered.map { it.codexId })
    }

    @Test
    fun `query repository stores query and scroll offset`() = runTest {
        val repo = InMemoryQueryRepository()
        val query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        repo.upsertAppliedQuery("unified", query)
        repo.upsertScrollOffset("hash-1", 420)

        assertNotNull(repo.observeAppliedQuery("unified").first())
        assertEquals(420, repo.getScrollOffset("hash-1"))
    }

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
    fun `settings repository normalizes source weights and scenario preset`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        repo.setSourceWeights(mapOf(SourceKey.PIXIV to 3.0, SourceKey.GELBOORU to 1.0))
        repo.setResolveUnknownAnimatedDurations(true)
        repo.setScenarioPreset(ScenarioPreset.PARTIAL_FAILURE)
        repo.setActiveProfile("profile-alt")

        val settings = repo.observeSettings().first()
        val pixivWeight = settings.runtime.sourceWeights.getValue(SourceKey.PIXIV)
        val gelbooruWeight = settings.runtime.sourceWeights.getValue(SourceKey.GELBOORU)

        assertEquals(1.0, pixivWeight + gelbooruWeight, 0.0001)
        assertTrue(pixivWeight > gelbooruWeight)
        assertTrue(settings.contentFilters.resolveUnknownAnimatedDurations)
        assertEquals(ScenarioPreset.PARTIAL_FAILURE, settings.scenarioPreset)
        assertEquals("profile-alt", settings.activeProfileId)
    }

    @Test
    fun `settings repository records provider health snapshots`() = runTest {
        val repo = InMemorySettingsRepository()

        repo.setProviderHealthSnapshots(
            listOf(
                ProviderHealthSnapshot(
                    source = SourceKey.IWARA,
                    status = ProviderHealthSnapshotStatus.DEGRADED,
                    checkedAtEpochMs = 22L,
                    latencyMs = 11L,
                    message = "Reachable but empty",
                )
            )
        )

        val snapshot = repo.observeSettings().first().providerHealth[SourceKey.IWARA]

        assertEquals(ProviderHealthSnapshotStatus.DEGRADED, snapshot?.status)
        assertEquals(11L, snapshot?.latencyMs)
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
                tag = "Blue Hair",
            )
        )
        var settings = repo.observeSettings().first()

        assertTrue(settings.recommendationProfiles.any { it.profileId == created.profileId && it.name == "Sketching" })
        assertEquals(created.profileId, settings.activeProfileId)
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile[created.profileId].orEmpty().map { it.tag })

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
                tag = "Blue Hair",
            )
        )
        assertFalse(
            repo.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        assertTrue(
            repo.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tag = "Blue Hair",
            )
        )

        var settings = repo.observeSettings().first()
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.GELBOORU }.map { it.tag })
        assertEquals(listOf("Blue Hair"), settings.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.PIXIV }.map { it.tag })

        assertTrue(
            repo.removeFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
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
        return Post(
            id = PostId(source = source, sourcePostId = id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 1000,
            height = 1500,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
        )
    }
}
