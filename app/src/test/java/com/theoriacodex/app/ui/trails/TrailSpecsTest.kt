package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.trails.TrailPage
import com.theoriacodex.app.trails.TrailPager
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.app.ui.trails.TrailTestData.related
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.domain.query.withinTermLimit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailSpecsTest {
    private val seed = post("seed")
    private val creator = CreatorProfile(SourceKey.PIXIV, "Mika", profileId = "7")
    private val tagPager = TrailPager { TrailPage(emptyList(), false) }
    private val creatorPager = TrailPager { TrailPage(emptyList(), false) }

    private class Sources(val creatorPager: TrailPager?, val similarPager: TrailPager?, val tagPager: TrailPager) : TrailSources {
        var taggedQuery: Query? = null
        override fun tag(query: Query) = tagPager.also { taggedQuery = query }
        override fun creator(creator: CreatorProfile) = creatorPager
        override fun similar(seed: Post) = similarPager
    }

    @Test
    fun `a tag trail is named after its tag and searches it`() {
        val sources = Sources(null, null, tagPager)
        val spec = trailSpec(TrailRequest.Tag("long_hair", SourceKey.PIXIV, null, seed), sources)!!

        assertEquals("long hair", spec.label)
        assertEquals(sources.taggedQuery, spec.query)
        assertSame(tagPager, spec.pager)
    }

    @Test
    fun `creator and Similar trails search nothing, and need a source that can supply them`() {
        val creatorSpec = trailSpec(TrailRequest.Creator(creator, seed), Sources(creatorPager, null, tagPager))!!
        assertEquals("Mika" to null, creatorSpec.label to creatorSpec.query)
        assertEquals("Similar", trailSpec(TrailRequest.Similar(seed), Sources(null, creatorPager, tagPager))?.label)

        assertNull(trailSpec(TrailRequest.Creator(creator, seed), Sources(null, null, tagPager)))
        assertNull(trailSpec(TrailRequest.Similar(seed), Sources(null, null, tagPager)))
    }

    @Test
    fun `a tag from a Unified level searches everywhere with that level's sort, else only the post's source`() {
        val unified = Query(QueryMode.Unified, listOf("cat"), emptyList(), SortMode.POPULAR, null, null)

        val everywhere = trailTagQuery("dog", SourceKey.DANBOORU, unified)
        assertEquals(QueryMode.Unified to SortMode.POPULAR, everywhere.mode to everywhere.sort)
        assertEquals(listOf("dog", "cat"), everywhere.includeTags)

        val local = trailTagQuery("dog", SourceKey.DANBOORU, context = null)
        assertEquals(QueryMode.Source(SourceKey.DANBOORU) to SortMode.NEWEST, local.mode to local.sort)
    }

    @Test
    fun `a tag trail keeps its level's media, dates and same-place exclusions`() {
        val dates = DateRange.Between(1L, 2L)
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("cat"), listOf("comic", "dog"), SortMode.TOP, dates, null)
            .copy(media = MediaFilter.ANIMATED)

        val fork = trailTagQuery("dog", SourceKey.DANBOORU, level)
        assertEquals(listOf("dog", "cat"), fork.includeTags)
        assertEquals(listOf("comic"), fork.excludeTags)
        assertEquals(Triple(MediaFilter.ANIMATED, dates, SortMode.TOP), Triple(fork.media, fork.dateRange, fork.sort))

        assertEquals(emptyList<String>(), trailTagQuery("fox", SourceKey.GELBOORU, level).excludeTags)
    }

    @Test
    fun `a single-source tag trail drops exclusions its source has no room for`() {
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), emptyList(), listOf("comic"), SortMode.TOP, null, null)

        val fork = trailTagQuery("dog", SourceKey.DANBOORU, level, termLimit = SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true))

        assertEquals(listOf("dog") to emptyList<String>(), fork.includeTags to fork.excludeTags)
    }

    @Test
    fun `a tag trail narrows its level's search, keeping its mode, media and sort`() {
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("genshin_impact"), emptyList(), SortMode.TOP, null, null)
            .copy(media = MediaFilter.ANIMATED)

        val fork = trailTagQuery("blush", SourceKey.DANBOORU, level)

        assertEquals(listOf("blush", "genshin_impact"), fork.includeTags)
        assertEquals(Triple(QueryMode.Source(SourceKey.DANBOORU), MediaFilter.ANIMATED, SortMode.TOP), Triple(fork.mode, fork.media, fork.sort))
    }

    @Test
    fun `a tag the level already searches is not added twice, and forking again narrows again`() {
        val level = Query(QueryMode.Unified, listOf("cat", "dog"), emptyList(), SortMode.NEWEST, null, null)

        assertEquals(listOf("dog", "cat"), trailTagQuery("DOG", SourceKey.DANBOORU, level).includeTags.map(String::lowercase))

        val first = trailTagQuery("blush", SourceKey.DANBOORU, level)
        assertEquals(listOf("blush", "cat", "dog"), first.includeTags)
        assertEquals(listOf("smile", "blush", "cat", "dog"), trailTagQuery("smile", SourceKey.DANBOORU, first).includeTags)
    }

    @Test
    fun `a typed term of the same name is another constraint, so the trail keeps it`() {
        val artist = SearchTerm("foo", SearchFacet.ARTIST)
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("foo"), emptyList(), SortMode.NEWEST, null, null)
            .withIncludeTermGroups(listOf(SearchTermGroup.single(artist)))
            .copy(excludeTerms = listOf(SearchTerm("foo", SearchFacet.CHARACTER)))

        val fork = trailTagQuery("foo", SourceKey.DANBOORU, level)

        assertEquals(listOf(SearchTermGroup.single(SearchTerm("foo")), SearchTermGroup.single(artist)), fork.includeTermGroups)
        assertEquals(level.excludeTerms, fork.excludeTerms)
    }

    @Test
    fun `a tag trail from another source's level searches the tag alone`() {
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("cat"), emptyList(), SortMode.NEWEST, null, null)

        assertEquals(listOf("fox"), trailTagQuery("fox", SourceKey.GELBOORU, level).includeTags)
    }

    @Test
    fun `a narrowed trail over a limit keeps the tag, then the level's tags in order, then exclusions`() {
        val level = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("cat", "dog"), listOf("comic"), SortMode.NEWEST, null, null)

        val two = trailTagQuery("blush", SourceKey.DANBOORU, level, termLimit = SearchTermLimit(maxTerms = 2))
        assertEquals(listOf("blush", "cat") to emptyList<String>(), two.includeTags to two.excludeTags)

        val four = trailTagQuery("blush", SourceKey.DANBOORU, level, termLimit = SearchTermLimit(maxTerms = 4))
        assertEquals(listOf("blush", "cat", "dog") to listOf("comic"), four.includeTags to four.excludeTags)

        val unified = trailTagQuery("blush", SourceKey.DANBOORU, level.copy(mode = QueryMode.Unified), termLimit = SearchTermLimit(maxTerms = 1))
        assertEquals(listOf("blush", "cat", "dog"), unified.includeTags)
        assertEquals(listOf("blush"), unified.withinTermLimit(SearchTermLimit(maxTerms = 1)).includeTags)
    }

    @Test
    fun `a tag trail from an Animated only level without a query, such as a creator's, still asks for animated posts`() {
        assertEquals(MediaFilter.ANIMATED, trailTagQuery("dog", SourceKey.DANBOORU, context = null, animatedOnly = true).media)
        assertEquals(MediaFilter.ANY, trailTagQuery("dog", SourceKey.DANBOORU, context = null).media)
    }

    @Test
    fun `a fork keeps its level's phone filters, with its own query's media and dates`() {
        val parent = SearchVisibilityFilters(
            animatedOnly = false, hideWatched = true, dateRange = DateRange.Between(1L, 2L),
            animatedDurationRange = AnimatedDurationRange(minBucket = 3, maxBucket = 9),
        )
        val tagQuery = trailTagQuery("dog", SourceKey.DANBOORU, null).copy(media = MediaFilter.ANIMATED)

        assertEquals(
            SearchVisibilityFilters(animatedOnly = true, hideWatched = true),
            trailVisibilityFilters(parent, tagQuery),
        )
        assertEquals(SearchVisibilityFilters(hideWatched = true), trailVisibilityFilters(parent, query = null))
    }

    @Test
    fun `a creator trail accumulates pages of the creator's own source until the token runs out`() = runTest {
        val pages = mapOf(
            null to Page(listOf(post("a"), post("x", SourceKey.DANBOORU)), "2"),
            "2" to Page(listOf(post("a"), post("b")), null),
        )
        val learned = mutableListOf<Post>()
        val adapter = object : CreatorPostsSourceAdapter {
            override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?) = pages.getValue(pageToken)
        }
        val pager = creatorTrailPager(adapter, creator, learned::addAll, EmptyCoroutineContext)

        val first = pager.next()
        assertEquals(listOf("a") to true, first.posts.map { it.id.sourcePostId } to first.canLoadMore)
        val second = pager.next()
        assertEquals(listOf("a", "b") to false, second.posts.map { it.id.sourcePostId } to second.canLoadMore)
        assertEquals(4, learned.size)
    }

    @Test
    fun `a Similar trail is one page without its seed, only where the source answers it`() = runTest {
        val loader = related(setOf(SourceKey.PIXIV)) { listOf(seed, post("b")) }

        val page = similarTrailPager(loader, seed)!!.next()
        assertEquals(listOf("b"), page.posts.map { it.id.sourcePostId })
        assertFalse(page.canLoadMore)
        assertTrue(similarTrailPager(related(), seed) == null)
    }
}
