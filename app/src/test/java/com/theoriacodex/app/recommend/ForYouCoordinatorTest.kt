package com.theoriacodex.app.recommend

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouCoordinatorTest {
    @Test
    fun `source selection constrains recommendation tags and content then unified restores both sources`() = runTest {
        val pixiv = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val gelbooru = FakeAdapter(SourceKey.GELBOORU, "gelbooru-post")
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("pixiv favorite"),
        )
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.GELBOORU, "liked-gelbooru"),
            tags = listOf("gelbooru favorite"),
        )
        val coordinator = ForYouCoordinator(
            registry = registryOf(pixiv, gelbooru),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), coordinator.availableSourceSelections)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.results.map { it.id.source }.toSet())

        coordinator.setSourceSelection(SourceKey.PIXIV)

        assertEquals(SourceKey.PIXIV, coordinator.selectedSource)
        assertEquals(setOf(SourceKey.PIXIV), coordinator.seedSummaryBySource.keys)
        assertTrue(coordinator.results.isNotEmpty())
        assertTrue(coordinator.results.all { post -> post.id.source == SourceKey.PIXIV })
        assertEquals(listOf("pixiv favorite"), pixiv.lastSearchQuery?.includeTags)
        val gelbooruRequestsAfterSelection = gelbooru.requestedPageTokens.size

        coordinator.loadNextPage()

        assertTrue(coordinator.results.all { post -> post.id.source == SourceKey.PIXIV })
        assertEquals(listOf(null, null, "next"), pixiv.requestedPageTokens)
        assertEquals(gelbooruRequestsAfterSelection, gelbooru.requestedPageTokens.size)

        coordinator.setSourceSelection(null)

        assertEquals(null, coordinator.selectedSource)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.results.map { it.id.source }.toSet())
    }

    @Test
    fun `blacklisting the only personalized seed leaves an honest empty feed`() = runTest {
        val adapter = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val likesRepository = InMemoryLikesRepository()
        val settingsRepository = InMemorySettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(
            profileId = profileId,
            postId = PostId(SourceKey.PIXIV, "liked-pixiv"),
            tags = listOf("only seed"),
        )
        settingsRepository.addForYouBlacklistEntry(
            profileId = profileId,
            source = SourceKey.PIXIV,
            tags = listOf("only seed"),
        )
        val coordinator = ForYouCoordinator(
            registry = registryOf(adapter),
            settingsRepository = settingsRepository,
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertTrue(coordinator.results.isEmpty())
        assertTrue(coordinator.seedSummaryBySource.isEmpty())
        assertEquals("empty-seed", coordinator.seedId)
        assertFalse(coordinator.canLoadMore)
        assertNull(coordinator.errorMessage)
        assertTrue(adapter.requestedPageTokens.isEmpty())
    }

    @Test
    fun `one failed source does not discard healthy recommendations`() = runTest {
        val healthy = FakeAdapter(SourceKey.PIXIV, "pixiv-post")
        val failing = FakeAdapter(SourceKey.GELBOORU, "gelbooru-post", failSearch = true)
        val likesRepository = InMemoryLikesRepository()
        val profileId = defaultRecommendationProfiles().first().profileId
        likesRepository.toggleLike(profileId, PostId(SourceKey.PIXIV, "liked-pixiv"), listOf("pixiv seed"))
        likesRepository.toggleLike(profileId, PostId(SourceKey.GELBOORU, "liked-gelbooru"), listOf("gelbooru seed"))
        val coordinator = ForYouCoordinator(
            registry = registryOf(healthy, failing),
            settingsRepository = InMemorySettingsRepository(),
            likesRepository = likesRepository,
        )

        coordinator.initialize()
        coordinator.refresh(shuffle = false)

        assertEquals(listOf(SourceKey.PIXIV), coordinator.results.map { post -> post.id.source })
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), coordinator.seedSummaryBySource.keys)
        assertTrue(coordinator.statuses.any { status -> status.source == SourceKey.GELBOORU })
        assertNull(coordinator.errorMessage)
    }

    private fun registryOf(vararg adapters: SourceAdapter): SourceAdapterRegistry {
        val adaptersBySource = adapters.associateBy { adapter -> adapter.sourceKey }
        val orchestrator = UnifiedSearchOrchestrator(adaptersBySource)
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = adaptersBySource.keys
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adaptersBySource[sourceKey]
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
        }
    }

    private class FakeAdapter(
        override val sourceKey: SourceKey,
        postId: String,
        private val failSearch: Boolean = false,
    ) : SourceAdapter {
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
            supportsDateRangeServerSide = true,
            supportsMinScoreServerSide = true,
            requiresCredentials = false,
        )
        private val post = testPost(
            source = sourceKey,
            sourcePostId = postId,
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = listOf("favorite"),
            rawTags = listOf("favorite"),
            authorName = null,
            createdAtEpochMs = null,
        )
        var lastSearchQuery: Query? = null
            private set
        val requestedPageTokens = mutableListOf<String?>()

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            if (failSearch) error("$sourceKey failed")
            lastSearchQuery = query
            requestedPageTokens += pageToken
            val pagePost = if (pageToken == null) {
                post
            } else {
                post.copy(id = post.id.copy(sourcePostId = "${post.id.sourcePostId}-$pageToken"))
            }
            return Page(
                items = listOf(pagePost),
                nextPageToken = if (pageToken == null) "next" else null,
            )
        }

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

        override suspend fun resolvePost(id: PostId): Post? = post.takeIf { candidate -> candidate.id == id }
    }
}
