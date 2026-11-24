package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouCoordinatorTest {
    @Test fun `each interest is its own single-source lane with its own continuation`() = runTest {
        val adapter = FakeAdapter(SourceKey.GELBOORU)
        val service = testForYouCoordinator(registryOf(adapter), likesRepository = liked(SourceKey.GELBOORU, "cloud", "river", "stone"))

        val lanes = service.lanes(request(service))

        assertTrue(lanes.size >= 3)
        assertEquals(lanes.size, lanes.map { it.id }.distinct().size)
        assertTrue(lanes.all { it.source == SourceKey.GELBOORU && it.cursor.pageTokens.keys == setOf(SourceKey.GELBOORU) })
        val (first, second) = lanes
        val firstPage = service.loadPage(first.cursor, setOf(first.source))
        assertEquals(first.tags, adapter.lastSearchQuery?.includeTags)
        service.loadPage(first.cursor.accept(firstPage, setOf(first.source)), setOf(first.source))
        assertEquals("next", adapter.requestedPageTokens.last())
        service.loadPage(second.cursor, setOf(second.source))
        assertNull(adapter.requestedPageTokens.last())
        assertEquals(second.tags, adapter.lastSearchQuery?.includeTags)
    }

    @Test fun `lanes take every source in turn and a selected source keeps only its own`() = runTest {
        val likes = liked(SourceKey.PIXIV, "cloud", "river").also { addLikes(it, SourceKey.GELBOORU, "cloud", "river") }
        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.PIXIV), FakeAdapter(SourceKey.GELBOORU)), likesRepository = likes)

        val mixed = service.lanes(request(service))
        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), mixed.take(2).mapTo(mutableSetOf()) { it.source })

        val only = service.lanes(request(service).copy(selectedSource = SourceKey.PIXIV))
        assertTrue(only.isNotEmpty() && only.all { it.source == SourceKey.PIXIV })
    }

    @Test fun `pixiv likes seed tag-compatible sources through learned translations`() = runTest {
        val likes = InMemoryLikesRepository().apply { toggleLike(PROFILE, PostId(SourceKey.PIXIV, "liked"), listOf("初音ミク")) }
        val store = object : TagSuggestionStore by NoOpTagSuggestionStore {
            override fun get(source: SourceKey, limit: Int) = listOf(
                TagSuggestion("初音ミク", "tag", null, alternateText = "Hatsune Miku"),
            ).takeIf { source == SourceKey.PIXIV }.orEmpty()
        }
        val service = testForYouCoordinator(
            registryOf(FakeAdapter(SourceKey.PIXIV), FakeAdapter(SourceKey.GELBOORU)), likesRepository = likes, tagSuggestionStore = store,
        )

        val tagsBySource = service.lanes(request(service)).associate { it.source to it.tags }

        assertEquals(listOf("初音ミク"), tagsBySource[SourceKey.PIXIV])
        assertEquals(listOf("hatsune_miku"), tagsBySource[SourceKey.GELBOORU])
    }

    @Test fun `a tag saved in pixiv's spelling seeds the boorus in theirs`() = runTest {
        val store = object : TagSuggestionStore by NoOpTagSuggestionStore {
            override fun get(source: SourceKey, limit: Int) = listOf(
                TagSuggestion("初音ミク", "tag", null, alternateText = "Hatsune Miku"),
            ).takeIf { source == SourceKey.PIXIV }.orEmpty()
        }
        val saved = TagAffinityInputs(interest = mapOf("初音ミク" to EXPLICIT_WEIGHT), explicit = setOf("初音ミク"))
        val service = testForYouCoordinator(
            registryOf(FakeAdapter(SourceKey.PIXIV), FakeAdapter(SourceKey.GELBOORU)), tagSuggestionStore = store, tagAffinity = saved,
        )

        val tagsBySource = service.lanes(request(service)).associate { it.source to it.tags }

        assertEquals(listOf("初音ミク"), tagsBySource[SourceKey.PIXIV])
        assertEquals(listOf("hatsune_miku"), tagsBySource[SourceKey.GELBOORU])
    }

    @Test fun `a profile with no likes gets trending lanes and likes never fetch trending`() = runTest {
        val adapter = FakeAdapter(SourceKey.GELBOORU).apply {
            trendingCandidates = listOf("sky", "sea", "sand", "snow").map { TagSuggestion(it, "tag", null) }
        }
        val cold = testForYouCoordinator(registryOf(adapter))
        assertEquals(listOf(listOf("sky"), listOf("sea"), listOf("sand")), cold.lanes(request(cold)).map { it.tags })
        assertEquals("sky", cold.lanes(request(cold)).first().label)

        val warm = FakeAdapter(SourceKey.GELBOORU)
        val service = testForYouCoordinator(registryOf(warm), likesRepository = liked(SourceKey.GELBOORU, "cloud"))
        assertEquals(listOf(listOf("cloud")), service.lanes(request(service)).map { it.tags })
        assertEquals(0, warm.trendingRequests)
    }

    @Test fun `a profile that only keeps Codex posts gets lanes from them, not trending`() = runTest {
        val adapter = FakeAdapter(SourceKey.GELBOORU).apply { trendingCandidates = listOf(TagSuggestion("sky", "tag", null)) }
        val codexOnly = TagAffinityInputs(interest = mapOf("cloud" to 3.0), keptByTag = mapOf(SourceKey.GELBOORU to mapOf("cloud" to 3)),
            keptBySource = mapOf(SourceKey.GELBOORU to 3))
        val service = testForYouCoordinator(registryOf(adapter), tagAffinity = codexOnly)

        assertEquals(listOf(listOf("cloud")), service.lanes(request(service)).map { it.tags })
        assertEquals(0, adapter.trendingRequests)
    }

    @Test fun `a tag the source has no posts for gives up its slot, not its partner's`() = runTest {
        val likes = InMemoryLikesRepository().apply {
            listOf("a", "b").forEach { id -> toggleLike(PROFILE, PostId(SourceKey.GELBOORU, id), listOf("cloud", "river")) }
        }
        val store = object : TagSuggestionStore by NoOpTagSuggestionStore {
            override fun getCount(source: SourceKey, tag: String): Int? = 0.takeIf { tag == "cloud" }
        }
        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.GELBOORU)), likesRepository = likes, tagSuggestionStore = store)

        assertEquals(listOf(listOf("river")), service.lanes(request(service)).map { it.tags })
    }

    @Test fun `hidden tags drop their lanes and the media filter reaches each lane's query`() = runTest {
        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.GELBOORU)), likesRepository = liked(SourceKey.GELBOORU, "cloud", "river"))
        val hidden = AppSettings().let { it.copy(forYouBlacklistByProfile = mapOf(it.activeProfileId to listOf(ForYouBlacklistEntry(SourceKey.GELBOORU, listOf("cloud"))))) }

        val lanes = service.lanes(request(service, hidden).copy(animatedOnly = true, sortMode = SortMode.POPULAR))

        assertTrue(lanes.isNotEmpty() && lanes.none { "cloud" in it.tags })
        val query = lanes.first().let { requireNotNull(it.cursor.queriesBySource[it.source]) }
        assertEquals(MediaFilter.ANIMATED, query.media)
        assertEquals(SortMode.POPULAR, query.sort)
    }

    @Test fun `a profile without interests plans no lanes, and a lane's page loads its posts`() = runTest {
        val empty = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.GELBOORU)))
        assertTrue(empty.lanes(request(empty)).isEmpty())

        val service = testForYouCoordinator(registryOf(FakeAdapter(SourceKey.GELBOORU)), likesRepository = liked(SourceKey.GELBOORU, "cloud"))
        val lane = service.lanes(request(service)).first()
        assertEquals(1, service.loadPage(lane.cursor, setOf(lane.source)).items.size)
    }

    @Test fun `a failed page reports its source and keeps the page for a retry`() = runTest {
        val adapter = FakeAdapter(SourceKey.GELBOORU)
        val service = testForYouCoordinator(registryOf(adapter), likesRepository = liked(SourceKey.GELBOORU, "cloud"))
        val lane = service.lanes(request(service)).first()
        adapter.failSearch = true

        val failed = service.loadPage(lane.cursor, setOf(lane.source))

        assertEquals(SourceRunState.FAILED, failed.statuses.single().state)
        val retry = lane.cursor.accept(failed, setOf(lane.source))
        assertTrue(lane.source in retry.pageTokens)
        adapter.failSearch = false
        assertFalse(service.loadPage(retry, setOf(lane.source)).items.isEmpty())
    }

    private fun request(service: ForYouCoordinator, settings: AppSettings = AppSettings()) =
        ForYouFeedRequest(settings, service.availableSources(settings), null, SortMode.NEWEST)

    private suspend fun liked(source: SourceKey, vararg tags: String): InMemoryLikesRepository = InMemoryLikesRepository().also { addLikes(it, source, *tags) }

    private suspend fun addLikes(likes: InMemoryLikesRepository, source: SourceKey, vararg tags: String) {
        tags.forEach { tag -> likes.toggleLike(PROFILE, PostId(source, "liked-$tag"), listOf(tag)) }
    }

    private fun registryOf(vararg adapters: SourceAdapter): SourceAdapterRegistry {
        val adaptersBySource = adapters.associateBy { adapter -> adapter.sourceKey }
        val orchestrator = UnifiedSearchOrchestrator(adaptersBySource, providerContext = EmptyCoroutineContext)
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = adaptersBySource.keys
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adaptersBySource[sourceKey]
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
        }
    }

    private class FakeAdapter(override val sourceKey: SourceKey, var failSearch: Boolean = false) : SourceAdapter {
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
        )
        private val post = testPost(source = sourceKey, sourcePostId = "post", canonicalTags = listOf("favorite"), rawTags = listOf("favorite"))
        var lastSearchQuery: Query? = null
            private set
        val requestedPageTokens = mutableListOf<String?>()
        var trendingCandidates: List<TagSuggestion> = emptyList()
        var trendingRequests = 0

        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            if (failSearch) error("$sourceKey failed")
            lastSearchQuery = query
            requestedPageTokens += pageToken
            val pagePost = post.copy(id = post.id.copy(sourcePostId = "${query.includeTags.joinToString("+")}-${pageToken ?: "first"}"))
            return Page(items = listOf(pagePost), nextPageToken = if (pageToken == null) "next" else null)
        }

        override suspend fun trendingTags(limit: Int): List<TagSuggestion> {
            trendingRequests += 1
            return trendingCandidates
        }

        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

        override suspend fun resolvePost(id: PostId): Post? = post.takeIf { candidate -> candidate.id == id }
    }

    private companion object {
        val PROFILE = AppSettings().activeProfileId
    }
}
