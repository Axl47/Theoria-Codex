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
            pages = mapOf(
                null to Page(items = listOf(samplePost("1"), samplePost("2")), nextPageToken = "next"),
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
            pages = mapOf(
                null to Page(items = listOf(samplePost("1")), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(samplePost("2")), nextPageToken = null),
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
            pages = mapOf(
                null to Page(items = listOf(samplePost("1")), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(samplePost("2")), nextPageToken = null),
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
            pages = mapOf(
                null to Page(items = listOf(samplePost("1"), samplePost("2")), nextPageToken = null),
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

    private fun sampleCreator(): CreatorProfile {
        return CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "creator_name",
            profileId = "201823",
            profileUrl = "https://www.pixiv.net/en/users/201823",
            uploadsQuery = "201823",
        )
    }

    private fun samplePost(id: String): Post {
        return Post(
            id = PostId(source = SourceKey.PIXIV, sourcePostId = id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 100,
            height = 100,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
            creatorProfile = sampleCreator(),
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
    val pages: Map<String?, Page<Post>>,
) : SourceAdapter, CreatorPostsSourceAdapter {
    val requestedTokens = mutableListOf<String?>()

    override val sourceKey: SourceKey = SourceKey.PIXIV
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
            mode = QueryMode.Source(SourceKey.PIXIV),
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
