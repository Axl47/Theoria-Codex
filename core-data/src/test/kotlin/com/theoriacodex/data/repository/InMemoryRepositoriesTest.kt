package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
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
    fun `query repository stores applied query`() = runTest {
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

        assertNotNull(repo.observeAppliedQuery("unified").first())
    }

    @Test
    fun `recents repository dedupes watched posts and searches by newest activity`() = runTest {
        var now = 1_000L
        val repo = InMemoryRecentsRepository(
            watchedLimit = 2,
            searchLimit = 2,
            clock = { now },
        )
        val firstPost = samplePost("1", source = SourceKey.PIXIV)
        val secondPost = samplePost("2", source = SourceKey.GELBOORU)
        val thirdPost = samplePost("3", source = SourceKey.AIBOORU)
        val firstQuery = sampleQuery(includeTags = listOf("landscape"))
        val secondQuery = sampleQuery(includeTags = listOf("portrait"))
        val thirdQuery = sampleQuery(includeTags = listOf("city"))

        repo.recordWatchedPost(firstPost, ViewerStreamSource.SEARCH, "hash-1")
        now += 1
        repo.recordWatchedPost(secondPost, ViewerStreamSource.FOR_YOU, "hash-2")
        now += 1
        repo.recordWatchedPost(firstPost.copy(title = "updated"), ViewerStreamSource.CODEX, "hash-3")
        now += 1
        repo.recordWatchedPost(thirdPost, ViewerStreamSource.CREATOR_PROFILE, "hash-4")
        now += 1
        repo.recordSearch(firstQuery, "query-1")
        now += 1
        repo.recordSearch(secondQuery, "query-2")
        now += 1
        repo.recordSearch(
            firstQuery.copy(excludeTerms = listOf(SearchTerm(value = "sketch"))),
            "query-1",
        )
        now += 1
        repo.recordSearch(thirdQuery, "query-3")

        val watched = repo.observeWatchedPosts().first()
        val searches = repo.observeSearches().first()
        val activity = repo.observeActivity().first()

        assertEquals(listOf(thirdPost.id, firstPost.id), watched.map { entry -> entry.post.id })
        assertEquals("updated", watched[1].post.title)
        assertEquals(ViewerStreamSource.CODEX, watched[1].origin)
        assertEquals(listOf("query-3", "query-1"), searches.map { entry -> entry.queryHash })
        assertEquals(listOf("sketch"), searches[1].query.excludeTags)
        assertEquals(searches.first().searchedAtEpochMs, activity.first().occurredAtEpochMs)
    }

    @Test
    fun `recents repository clears watched and searches independently`() = runTest {
        val repo = InMemoryRecentsRepository(clock = { 1L })

        repo.recordWatchedPost(samplePost("1"), ViewerStreamSource.SEARCH, "hash")
        repo.recordSearch(sampleQuery(), "query")
        repo.clearWatchedPosts()

        assertTrue(repo.observeWatchedPosts().first().isEmpty())
        assertEquals(1, repo.observeSearches().first().size)

        repo.clearSearches()

        assertTrue(repo.observeSearches().first().isEmpty())

        repo.recordWatchedPost(samplePost("2"), ViewerStreamSource.CODEX, null)
        repo.recordSearch(sampleQuery(includeTags = listOf("city")), "query-2")
        repo.clearAll()

        assertTrue(repo.observeWatchedPosts().first().isEmpty())
        assertTrue(repo.observeSearches().first().isEmpty())
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
        repo.setInvertMultiImageScrollDirection(true)
        repo.setScenarioPreset(ScenarioPreset.PARTIAL_FAILURE)
        repo.setActiveProfile("profile-alt")

        val settings = repo.observeSettings().first()
        val pixivWeight = settings.runtime.sourceWeights.getValue(SourceKey.PIXIV)
        val gelbooruWeight = settings.runtime.sourceWeights.getValue(SourceKey.GELBOORU)

        assertEquals(1.0, pixivWeight + gelbooruWeight, 0.0001)
        assertTrue(pixivWeight > gelbooruWeight)
        assertTrue(settings.contentFilters.resolveUnknownAnimatedDurations)
        assertTrue(settings.viewer.invertMultiImageScrollDirection)
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

    private fun sampleQuery(includeTags: List<String> = listOf("landscape")): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = includeTags,
            excludeTags = emptyList(),
            sort = SortMode.TOP,
            dateRange = DateRange(fromEpochMs = 100L, toEpochMs = 200L),
            minScore = 20,
        )
    }

    private fun samplePost(id: String, source: SourceKey = SourceKey.PIXIV): Post {
        return repositoryTestPost(id = id, source = source)
    }
}
