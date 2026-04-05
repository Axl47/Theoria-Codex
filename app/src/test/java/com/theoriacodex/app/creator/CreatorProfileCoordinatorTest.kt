package com.theoriacodex.app.creator

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorProfileCoordinatorTest {
    @Test
    fun `open loads first page and buildViewerLaunchContext uses creator stream`() = runTest {
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.PIXIV,
            pages = mapOf(
                null to Page(
                    items = listOf(
                        samplePost("1", sampleCreator()),
                        samplePost("2", sampleCreator()),
                    ),
                    nextPageToken = "next",
                ),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(sampleCreator())
        val context = coordinator.buildViewerLaunchContext(startIndex = 1, scrollOffsetHint = 42)

        assertEquals(listOf("1", "2"), coordinator.results.map { it.id.sourcePostId })
        assertTrue(coordinator.canLoadMore)
        assertEquals(SourceKey.PIXIV, coordinator.activeCreator?.source)
        assertEquals("creator:PIXIV:201823", context.queryHash)
        assertEquals(1, context.startIndex)
    }

    @Test
    fun `loadNextPage appends results and updates load more state`() = runTest {
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.PIXIV,
            pages = mapOf(
                null to Page(items = listOf(samplePost("1", sampleCreator())), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(samplePost("2", sampleCreator())), nextPageToken = null),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(sampleCreator())
        coordinator.loadNextPage()

        assertEquals(listOf("1", "2"), coordinator.results.map { it.id.sourcePostId })
        assertFalse(coordinator.canLoadMore)
        assertEquals(listOf(null, "page-2"), adapter.requestedTokens)
    }

    @Test
    fun `refresh resets pagination back to first page`() = runTest {
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.PIXIV,
            pages = mapOf(
                null to Page(items = listOf(samplePost("1", sampleCreator())), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(samplePost("2", sampleCreator())), nextPageToken = null),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(sampleCreator())
        coordinator.loadNextPage()
        coordinator.refresh()

        assertEquals(listOf("1"), coordinator.results.map { it.id.sourcePostId })
        assertTrue(coordinator.canLoadMore)
        assertEquals(listOf(null, "page-2", null), adapter.requestedTokens)
    }

    @Test
    fun `unsupported adapter state is surfaced cleanly`() = runTest {
        val coordinator = CreatorProfileCoordinator(registry = unsupportedRegistry())

        coordinator.open(sampleCreator())

        assertTrue(coordinator.results.isEmpty())
        assertFalse(coordinator.canLoadMore)
        assertTrue(coordinator.errorMessage?.contains("not available", ignoreCase = true) == true)
    }

    @Test
    fun `coordinator results remain unfiltered and local visibility filtering is external`() = runTest {
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.PIXIV,
            pages = mapOf(
                null to Page(items = listOf(samplePost("1", sampleCreator()), samplePost("2", sampleCreator())), nextPageToken = null),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(sampleCreator())
        val filtered = com.theoriacodex.app.search.filterSearchResults(
            results = coordinator.results,
            filters = com.theoriacodex.app.search.SearchVisibilityFilters(hideLiked = true),
            likedPostIds = setOf(coordinator.results.last().id),
            savedPostIds = emptySet(),
        )

        assertEquals(2, coordinator.results.size)
        assertEquals(1, filtered.size)
    }

    @Test
    fun `iwara creator profile loads paged videos`() = runTest {
        val creator = sampleCreator(source = SourceKey.IWARA, profileId = "iwara-user-id")
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.IWARA,
            pages = mapOf(
                null to Page(items = listOf(samplePost("i1", creator)), nextPageToken = "1"),
                "1" to Page(items = listOf(samplePost("i2", creator)), nextPageToken = null),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(creator)
        coordinator.loadNextPage()

        assertEquals(listOf("i1", "i2"), coordinator.results.map { it.id.sourcePostId })
        assertEquals(listOf(null, "1"), adapter.requestedTokens)
        assertEquals(SourceKey.IWARA, coordinator.activeCreator?.source)
    }

    private fun sampleCreator(
        source: SourceKey = SourceKey.PIXIV,
        profileId: String = "201823",
    ): CreatorProfile {
        return CreatorProfile(
            source = source,
            displayName = "creator_name",
            profileId = profileId,
            profileUrl = if (source == SourceKey.IWARA) {
                "https://www.iwara.tv/profile/creator_name/videos"
            } else {
                "https://www.pixiv.net/en/users/$profileId"
            },
            uploadsQuery = profileId,
        )
    }

    private fun samplePost(id: String, creator: CreatorProfile): Post {
        return Post(
            id = PostId(source = creator.source, sourcePostId = id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 100,
            height = 100,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
            creatorProfile = creator,
        )
    }

    private fun singleAdapterRegistry(adapter: SourceAdapter): SourceAdapterRegistry {
        val stubRegistry = StubAdapterRegistry()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapter.takeIf { it.sourceKey == sourceKey }
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = stubRegistry.unifiedOrchestrator()
        }
    }

    private fun unsupportedRegistry(): SourceAdapterRegistry {
        val stubRegistry = StubAdapterRegistry()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = setOf(SourceKey.PIXIV)
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = object : SourceAdapter {
                override val sourceKey: SourceKey = SourceKey.PIXIV
                override val capabilities: SourceCapabilities = defaultCapabilities()
                override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)
                override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
                override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
                override suspend fun quickQuery(kind: QuickQueryKind): Query {
                    return Query(
                        mode = QueryMode.Source(sourceKey),
                        includeTags = emptyList(),
                        excludeTags = emptyList(),
                        sort = SortMode.NEWEST,
                        dateRange = null,
                        minScore = null,
                    )
                }
                override suspend fun resolvePost(id: PostId): Post? = null
            }
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = stubRegistry.unifiedOrchestrator()
        }
    }

    private fun defaultCapabilities(): SourceCapabilities {
        return SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
            supportsDateRangeServerSide = true,
            supportsMinScoreServerSide = true,
            requiresCredentials = false,
        )
    }
}

private class FakeCreatorAdapter(
    adapterSourceKey: SourceKey,
    val pages: Map<String?, Page<Post>>,
) : SourceAdapter, CreatorPostsSourceAdapter {
    val requestedTokens = mutableListOf<String?>()

    override val sourceKey: SourceKey = adapterSourceKey
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = true,
        supportsMinScoreServerSide = true,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)
    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return Query(
            mode = QueryMode.Source(sourceKey),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
    override suspend fun resolvePost(id: PostId): Post? = null

    override suspend fun searchCreatorPosts(
        creator: CreatorProfile,
        pageToken: String?,
    ): Page<Post> {
        requestedTokens += pageToken
        return pages[pageToken] ?: Page(emptyList(), null)
    }
}
