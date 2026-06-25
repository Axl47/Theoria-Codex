package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBackedRepositoriesTest {
    @Test
    fun `codex repository persists codex and items across instances`() = runTest {
        val dir = Files.createTempDirectory("codex-store-").toFile()
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        first.addItem(created.codexId, samplePost("1", localPath = null, source = SourceKey.PIXIV))
        first.addItem(created.codexId, samplePost("2", localPath = null, source = SourceKey.AIBOORU))

        val second = FileBackedCodexRepository(dir)
        val bySource = second.observeCodexPosts(created.codexId, CodexSortMode.BY_SOURCE).first()

        assertEquals(1, second.observeCodices().first().size)
        assertEquals(2, second.observeCodexItems(created.codexId).first().size)
        assertEquals(SourceKey.AIBOORU, bySource.first().id.source)
        assertNotNull(second.getPost(PostId(SourceKey.PIXIV, "1")))
    }

    @Test
    fun `codex repository persists creator profiles across instances`() = runTest {
        val dir = Files.createTempDirectory("codex-creator-profile-").toFile()
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val post = samplePost("1", localPath = null, source = SourceKey.PIXIV).copy(
            creatorProfile = CreatorProfile(
                source = SourceKey.PIXIV,
                displayName = "artist",
                profileId = "201823",
                profileUrl = "https://www.pixiv.net/en/users/201823",
                uploadsQuery = "201823",
            ),
        )

        first.addItem(created.codexId, post)

        val second = FileBackedCodexRepository(dir)
        val loaded = second.getPost(post.id)

        assertEquals("artist", loaded?.creatorProfile?.displayName)
        assertEquals("201823", loaded?.creatorProfile?.profileId)
        assertEquals("201823", loaded?.creatorProfile?.uploadsQuery)
    }

    @Test
    fun `codex repository persists media metadata across instances`() = runTest {
        val dir = Files.createTempDirectory("codex-progressive-urls-").toFile()
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val progressiveUrls = listOf(
            "https://gelbooru.com/sample/1.jpg",
            "https://gelbooru.com/alternate/1.jpg",
        )
        val previewProgressiveUrls = listOf(
            "https://gelbooru.com/thumb-small/1.jpg",
            "https://gelbooru.com/thumb-large/1.jpg",
        )
        val media = ImageRef(
            url = "https://gelbooru.com/full/1.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = progressiveUrls,
        )
        val post = samplePost("1", localPath = null, source = SourceKey.GELBOORU).copy(
            preview = ImageRef(
                url = "https://gelbooru.com/preview/1.jpg",
                localPath = null,
                mime = "image/jpeg",
                progressiveUrls = previewProgressiveUrls,
            ),
            full = media,
            media = listOf(media),
            durationMs = 12_345L,
        )

        first.addItem(created.codexId, post)

        val second = FileBackedCodexRepository(dir)
        val loaded = second.getPost(post.id)

        assertEquals(previewProgressiveUrls, loaded?.preview?.progressiveUrls)
        assertEquals(progressiveUrls, loaded?.full?.progressiveUrls)
        assertEquals(progressiveUrls, loaded?.media?.single()?.progressiveUrls)
        assertEquals(12_345L, loaded?.durationMs)
    }

    @Test
    fun `codex repository reads legacy post records without creator profile`() = runTest {
        val dir = Files.createTempDirectory("codex-legacy-post-record-").toFile()
        val storageFile = dir.resolve("codex_store.json")
        storageFile.writeText(
            """
            {
              "codices": [],
              "items": {},
              "posts": [
                {
                  "source": "PIXIV",
                  "sourcePostId": "1",
                  "previewUrl": "https://example.com/1.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": "https://example.com/full/1.jpg",
                  "fullLocalPath": null,
                  "fullMime": "image/jpeg",
                  "pageUrl": "https://example.com/post/1",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": ["landscape"],
                  "rawTags": ["landscape"],
                  "authorName": "artist",
                  "createdAtEpochMs": 1,
                  "media": [],
                  "title": "Legacy"
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = FileBackedCodexRepository(dir)
        val loaded = repository.getPost(PostId(SourceKey.PIXIV, "1"))

        assertNotNull(loaded)
        assertEquals(null, loaded?.creatorProfile)
        assertEquals("Legacy", loaded?.title)
        assertEquals(emptyList<String>(), loaded?.preview?.progressiveUrls)
        assertEquals(emptyList<String>(), loaded?.full?.progressiveUrls)
        assertEquals(null, loaded?.durationMs)
    }

    @Test
    fun `codex repository ensures stable system codex across restarts`() = runTest {
        val dir = Files.createTempDirectory("codex-likes-system-").toFile()
        val first = FileBackedCodexRepository(dir)
        first.ensureCodex(codexId = "system_likes_codex", name = "Likes")

        val second = FileBackedCodexRepository(dir)
        val existing = second.ensureCodex(codexId = "system_likes_codex", name = "Likes")
        val codices = second.observeCodices().first()

        assertEquals("system_likes_codex", existing.codexId)
        assertEquals("Likes", existing.name)
        assertEquals(1, codices.size)
    }

    @Test
    fun `codex repository appends numeric suffix for duplicate names across restarts`() = runTest {
        val dir = Files.createTempDirectory("codex-duplicate-names-").toFile()
        val first = FileBackedCodexRepository(dir)

        val alpha = first.createCodex("Favorites")
        val beta = first.createCodex("Favorites")
        val gamma = first.ensureCodex(codexId = "manual", name = "Favorites")
        first.renameCodex(beta.codexId, "Favorites")

        val second = FileBackedCodexRepository(dir)
        val names = second.observeCodices().first().associateBy({ it.codexId }, { it.name })

        assertEquals("Favorites", names[alpha.codexId])
        assertEquals("Favorites 2", names[beta.codexId])
        assertEquals("Favorites 3", names[gamma.codexId])
    }

    @Test
    fun `codex repository persists reorder across restarts`() = runTest {
        val dir = Files.createTempDirectory("codex-reorder-").toFile()
        val first = FileBackedCodexRepository(dir)
        val alpha = first.createCodex("Alpha")
        val beta = first.createCodex("Beta")
        val gamma = first.createCodex("Gamma")

        first.reorderCodex(codexId = gamma.codexId, targetIndex = 0)

        val second = FileBackedCodexRepository(dir)
        val orderedIds = second.observeCodices().first().map { codex -> codex.codexId }

        assertEquals(listOf(gamma.codexId, alpha.codexId, beta.codexId), orderedIds)
    }

    @Test
    fun `query repository persists applied query and scroll offsets`() = runTest {
        val dir = Files.createTempDirectory("query-store-").toFile()
        val first = FileBackedQueryRepository(dir)
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = listOf("comic"),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = 10,
        )
        first.upsertAppliedQuery("source:PIXIV", query)
        first.upsertScrollOffset("qhash", 320)

        val second = FileBackedQueryRepository(dir)

        assertNotNull(second.observeAppliedQuery("source:PIXIV").first())
        assertEquals(320, second.getScrollOffset("qhash"))
    }

    @Test
    fun `recents repository persists watched posts searches and activity order`() = runTest {
        val dir = Files.createTempDirectory("recents-store-").toFile()
        var now = 10_000L
        val first = FileBackedRecentsRepository(
            baseDirectory = dir,
            watchedLimit = 2,
            searchLimit = 2,
            clock = { now },
        )
        val media = ImageRef(
            url = "https://example.com/full-video.mp4",
            localPath = null,
            mime = "video/mp4",
            progressiveUrls = listOf("https://example.com/sample-video.mp4"),
        )
        val pixivPost = samplePost("1", localPath = null, source = SourceKey.PIXIV).copy(
            title = "First viewed",
            media = listOf(media),
            durationMs = 42_000L,
        )
        val gelbooruPost = samplePost("2", localPath = null, source = SourceKey.GELBOORU)
        val aibooruPost = samplePost("3", localPath = null, source = SourceKey.AIBOORU)
        val firstQuery = sampleQuery(includeTags = listOf("landscape"))
        val secondQuery = sampleQuery(includeTags = listOf("portrait"))
        val thirdQuery = sampleQuery(includeTags = listOf("city"))

        first.recordWatchedPost(pixivPost, ViewerStreamSource.SEARCH, "query-1")
        now += 1
        first.recordWatchedPost(gelbooruPost, ViewerStreamSource.FOR_YOU, "query-2")
        now += 1
        first.recordWatchedPost(pixivPost.copy(title = "Viewed again"), ViewerStreamSource.CODEX, "query-3")
        now += 1
        first.recordWatchedPost(aibooruPost, ViewerStreamSource.CREATOR_PROFILE, "query-4")
        now += 1
        first.recordSearch(firstQuery, "search-1")
        now += 1
        first.recordSearch(secondQuery, "search-2")
        now += 1
        first.recordSearch(firstQuery.copy(excludeTags = listOf("comic")), "search-1")
        now += 1
        first.recordSearch(thirdQuery, "search-3")

        val second = FileBackedRecentsRepository(dir)
        val watched = second.observeWatchedPosts().first()
        val searches = second.observeSearches().first()
        val activity = second.observeActivity().first()

        assertEquals(listOf(aibooruPost.id, pixivPost.id), watched.map { entry -> entry.post.id })
        assertEquals("Viewed again", watched[1].post.title)
        assertEquals(ViewerStreamSource.CODEX, watched[1].origin)
        assertEquals("query-3", watched[1].originQueryHash)
        assertEquals(listOf("https://example.com/sample-video.mp4"), watched[1].post.media.single().progressiveUrls)
        assertEquals(42_000L, watched[1].post.durationMs)
        assertEquals(listOf("search-3", "search-1"), searches.map { entry -> entry.queryHash })
        assertEquals(listOf("comic"), searches[1].query.excludeTags)
        assertEquals(searches.first().searchedAtEpochMs, activity.first().occurredAtEpochMs)
    }

    @Test
    fun `recents repository clears watched and search history independently across restarts`() = runTest {
        val dir = Files.createTempDirectory("recents-clear-store-").toFile()
        val first = FileBackedRecentsRepository(dir, clock = { 1L })

        first.recordWatchedPost(samplePost("1", localPath = null), ViewerStreamSource.SEARCH, "hash")
        first.recordSearch(sampleQuery(), "query")
        first.clearWatchedPosts()

        val second = FileBackedRecentsRepository(dir)
        assertTrue(second.observeWatchedPosts().first().isEmpty())
        assertEquals(1, second.observeSearches().first().size)

        second.clearSearches()
        val third = FileBackedRecentsRepository(dir)
        assertTrue(third.observeSearches().first().isEmpty())

        third.recordWatchedPost(samplePost("2", localPath = null), ViewerStreamSource.CODEX, null)
        third.recordSearch(sampleQuery(includeTags = listOf("city")), "query-2")
        third.clearAll()

        val fourth = FileBackedRecentsRepository(dir)
        assertTrue(fourth.observeWatchedPosts().first().isEmpty())
        assertTrue(fourth.observeSearches().first().isEmpty())
    }

    @Test
    fun `settings repository persists updates`() = runTest {
        val dir = Files.createTempDirectory("settings-store-").toFile()
        val first = FileBackedSettingsRepository(dir)
        first.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        first.setSourceWeights(mapOf(SourceKey.PIXIV to 4.0, SourceKey.GELBOORU to 1.0))
        first.setCacheFullImageOnSave(true)
        first.setResolveUnknownAnimatedDurations(true)
        first.setScenarioPreset(ScenarioPreset.EMPTY_RESULTS)
        first.setLastTab("codex")
        first.setActiveProfile("profile-alt")

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertTrue(loaded.contentFilters.resolveUnknownAnimatedDurations)
        assertEquals(ScenarioPreset.EMPTY_RESULTS, loaded.scenarioPreset)
        assertEquals("codex", loaded.lastSelectedTabRoute)
        assertEquals("profile-alt", loaded.activeProfileId)
        val total = loaded.runtime.sourceWeights.values.sum()
        assertEquals(1.0, total, 0.0001)
    }

    @Test
    fun `settings repository persists provider health snapshots`() = runTest {
        val dir = Files.createTempDirectory("settings-provider-health-").toFile()
        val first = FileBackedSettingsRepository(dir)
        first.setProviderHealthSnapshots(
            listOf(
                ProviderHealthSnapshot(
                    source = SourceKey.GELBOORU,
                    status = ProviderHealthSnapshotStatus.OK,
                    checkedAtEpochMs = 123L,
                    latencyMs = 45L,
                    message = "Returned 2 posts",
                ),
                ProviderHealthSnapshot(
                    source = SourceKey.PIXIV,
                    status = ProviderHealthSnapshotStatus.FAILED,
                    checkedAtEpochMs = 124L,
                    failureReason = "AUTH_REQUIRED",
                    message = "Missing credentials",
                ),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val health = second.observeSettings().first().providerHealth

        assertEquals(ProviderHealthSnapshotStatus.OK, health[SourceKey.GELBOORU]?.status)
        assertEquals(45L, health[SourceKey.GELBOORU]?.latencyMs)
        assertEquals("AUTH_REQUIRED", health[SourceKey.PIXIV]?.failureReason)
    }

    @Test
    fun `settings repository defaults unknown duration resolution on for old files`() = runTest {
        val dir = Files.createTempDirectory("settings-store-old-").toFile()
        dir.resolve("settings_store.json").writeText(
            """
                {
                  "enabledSources": ["PIXIV", "GELBOORU"],
                  "cacheFullImageOnSave": true
                }
            """.trimIndent(),
        )

        val loaded = FileBackedSettingsRepository(dir).observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertTrue(loaded.contentFilters.resolveUnknownAnimatedDurations)
    }

    @Test
    fun `settings repository persists dynamic recommendation profiles`() = runTest {
        val dir = Files.createTempDirectory("settings-profiles-").toFile()
        val first = FileBackedSettingsRepository(dir)
        val created = first.addRecommendationProfile("Anime Mood")
        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = created.profileId,
                source = SourceKey.PIXIV,
                tags = listOf("portrait", "artist"),
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = created.profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        first.setActiveProfile(created.profileId)

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertEquals(created.profileId, loaded.activeProfileId)
        assertTrue(loaded.recommendationProfiles.any { it.profileId == created.profileId && it.name == "Anime Mood" })
        assertEquals(1, loaded.forYouBlacklistByProfile[created.profileId].orEmpty().size)
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[created.profileId].orEmpty().map { it.tag })
        assertTrue(second.removeRecommendationProfile(created.profileId))
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().recommendationProfiles.none { it.profileId == created.profileId })
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertTrue(third.observeSettings().first().favoriteTagsByProfile[created.profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists for you blacklist entries`() = runTest {
        val dir = Files.createTempDirectory("settings-for-you-blacklist-").toFile()
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("Cloud", "Sky"),
            )
        )
        assertFalse(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("sky", "cloud"),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("cloud", "sky"), loaded.forYouBlacklistByProfile[profileId].orEmpty().first().tags)

        assertTrue(
            second.removeForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("cloud", "sky"),
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists source-aware favorite tags`() = runTest {
        val dir = Files.createTempDirectory("settings-favorite-tags-").toFile()
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        assertFalse(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tag = "Blue Hair",
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.GELBOORU }.map { it.tag })
        assertEquals(listOf("Blue Hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.PIXIV }.map { it.tag })

        assertTrue(
            second.removeFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertEquals(1, third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().size)
        assertEquals(
            SourceKey.PIXIV,
            third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().first().source,
        )
    }

    @Test
    fun `likes repository persists toggles and profile isolation`() = runTest {
        val dir = Files.createTempDirectory("likes-store-").toFile()
        val first = FileBackedLikesRepository(dir)
        val pixivPost = samplePost("10", localPath = null, source = SourceKey.PIXIV)
        val gelbooruPost = samplePost("11", localPath = null, source = SourceKey.GELBOORU)

        first.toggleLike(
            profileId = "profile-main",
            postId = pixivPost.id,
            tags = listOf("cloud", "sky"),
        )
        first.toggleLike(
            profileId = "profile-alt",
            postId = gelbooruPost.id,
            tags = listOf("sunset"),
        )

        val second = FileBackedLikesRepository(dir)
        val user1Likes = second.observeLikes("profile-main").first()
        val user2Likes = second.observeLikes("profile-alt").first()

        assertEquals(1, user1Likes.size)
        assertEquals(pixivPost.id, user1Likes.first().postId)
        assertEquals(listOf("cloud", "sky"), user1Likes.first().tags)
        assertEquals(1, user2Likes.size)
        assertEquals(gelbooruPost.id, user2Likes.first().postId)

        second.clearLikes("profile-main")
        val third = FileBackedLikesRepository(dir)
        assertTrue(third.observeLikes("profile-main").first().isEmpty())
        assertEquals(1, third.observeLikes("profile-alt").first().size)
    }

    @Test
    fun `cache repository writes entries and survives restart`() = runTest {
        val dir = Files.createTempDirectory("cache-store-").toFile()
        val sourceFile = File(dir, "source-thumb.jpg")
        sourceFile.writeText("image-bytes")

        val first = FileBackedCacheRepository(dir)
        first.cacheThumbnail(samplePost("1", sourceFile.absolutePath))

        val second = FileBackedCacheRepository(dir)
        val snapshot = second.observeSnapshot().first()

        assertEquals(1, snapshot.thumbnailCount)
        assertEquals(0, snapshot.fullImageCount)
    }

    @Test
    fun `ui restore repository persists tab scroll and viewer context`() = runTest {
        val dir = Files.createTempDirectory("ui-restore-store-").toFile()
        val first = FileBackedUiRestoreRepository(dir)
        val context = ViewerLaunchContext(
            queryHash = "qhash",
            startIndex = 4,
            streamSource = ViewerStreamSource.CODEX,
            scrollOffsetHint = 90,
        )

        first.setLastTab("settings")
        first.setSearchScrollState("qhash", SearchScrollState(firstVisibleItemIndex = 3, firstVisibleItemOffsetPx = 28))
        first.setViewerLaunchContext(context)

        val second = FileBackedUiRestoreRepository(dir)

        assertEquals("settings", second.getLastTab())
        assertEquals(3, second.getSearchScrollState("qhash")?.firstVisibleItemIndex)
        assertEquals(context, second.observeViewerLaunchContext().first())
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

    private fun samplePost(id: String, localPath: String?, source: SourceKey = SourceKey.PIXIV): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = localPath, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 100,
            height = 100,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
            creatorProfile = CreatorProfile(
                source = source,
                displayName = "artist",
                profileId = "profile-$id",
                profileUrl = "https://example.com/creator/$id",
                uploadsQuery = "uploads-$id",
            ),
        )
    }
}
