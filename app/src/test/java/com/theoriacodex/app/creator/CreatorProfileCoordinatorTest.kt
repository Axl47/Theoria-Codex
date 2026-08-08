package com.theoriacodex.app.creator

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
                        testPost(sourcePostId = "1", creatorProfile = sampleCreator()),
                        testPost(sourcePostId = "2", creatorProfile = sampleCreator()),
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
                null to Page(items = listOf(testPost(sourcePostId = "1", creatorProfile = sampleCreator())), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(testPost(sourcePostId = "2", creatorProfile = sampleCreator())), nextPageToken = null),
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
                null to Page(items = listOf(testPost(sourcePostId = "1", creatorProfile = sampleCreator())), nextPageToken = "page-2"),
                "page-2" to Page(items = listOf(testPost(sourcePostId = "2", creatorProfile = sampleCreator())), nextPageToken = null),
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
                null to Page(
                    items = listOf(
                        testPost(sourcePostId = "1", creatorProfile = sampleCreator()),
                        testPost(sourcePostId = "2", creatorProfile = sampleCreator()),
                    ),
                    nextPageToken = null,
                ),
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
                null to Page(
                    items = listOf(testPost(source = creator.source, sourcePostId = "i1", creatorProfile = creator)),
                    nextPageToken = "1",
                ),
                "1" to Page(
                    items = listOf(testPost(source = creator.source, sourcePostId = "i2", creatorProfile = creator)),
                    nextPageToken = null,
                ),
            ),
        )
        val coordinator = CreatorProfileCoordinator(registry = singleAdapterRegistry(adapter))

        coordinator.open(creator)
        coordinator.loadNextPage()

        assertEquals(listOf("i1", "i2"), coordinator.results.map { it.id.sourcePostId })
        assertEquals(listOf(null, "1"), adapter.requestedTokens)
        assertEquals(SourceKey.IWARA, coordinator.activeCreator?.source)
    }

    @Test
    fun `capability removal clears active creator and rejects late source result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val creator = sampleCreator(source = SourceKey.RULE34XXX, profileId = "rule34-creator")
        val adapter = FakeCreatorAdapter(
            adapterSourceKey = SourceKey.RULE34XXX,
            pages = mapOf(
                null to Page(
                    items = listOf(
                        testPost(
                            source = SourceKey.RULE34XXX,
                            sourcePostId = "stale",
                            creatorProfile = creator,
                        )
                    ),
                    nextPageToken = "next",
                ),
            ),
            searchStarted = started,
            searchRelease = release,
        )
        val capabilities = MutableStateFlow(setOf(SourceKey.RULE34XXX))
        val coordinator = CreatorProfileCoordinator(
            registry = mutableRegistry(capabilities, adapter),
        )

        val staleRequest = backgroundScope.launch { coordinator.open(creator) }
        started.await()

        capabilities.value = emptySet()
        assertEquals(
            CreatorSourceAvailabilityChange.RECONCILED,
            coordinator.onAvailableSourcesChanged(),
        )
        assertTrue(coordinator.results.isEmpty())
        assertFalse(coordinator.loading)
        assertFalse(coordinator.canLoadMore)
        assertTrue(coordinator.errorMessage?.contains("not available", ignoreCase = true) == true)

        release.complete(Unit)
        staleRequest.join()

        assertTrue(coordinator.results.isEmpty())
        assertFalse(coordinator.canLoadMore)
        assertTrue(coordinator.errorMessage?.contains("not available", ignoreCase = true) == true)
        assertEquals(creator, coordinator.activeCreator)
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

    private fun singleAdapterRegistry(adapter: SourceAdapter): SourceAdapterRegistry {
        val stubRegistry = StubAdapterRegistry()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapter.takeIf { it.sourceKey == sourceKey }
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = stubRegistry.unifiedOrchestrator()
        }
    }

    private fun mutableRegistry(
        capabilities: MutableStateFlow<Set<SourceKey>>,
        adapter: SourceAdapter,
    ): SourceAdapterRegistry {
        val stubRegistry = StubAdapterRegistry()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> {
                return setOf(adapter.sourceKey).intersect(capabilities.value)
            }
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
                return adapter.takeIf { sourceKey == adapter.sourceKey && sourceKey in capabilities.value }
            }
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
    private val searchStarted: CompletableDeferred<Unit>? = null,
    private val searchRelease: CompletableDeferred<Unit>? = null,
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
        searchStarted?.complete(Unit)
        searchRelease?.let { gate ->
            withContext(NonCancellable) { gate.await() }
        }
        requestedTokens += pageToken
        return pages[pageToken] ?: Page(emptyList(), null)
    }
}
