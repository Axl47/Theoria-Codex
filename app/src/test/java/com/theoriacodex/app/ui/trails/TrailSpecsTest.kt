package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.app.ui.trails.TrailTestData.related
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
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
        assertEquals(listOf("dog"), everywhere.includeTags)

        val local = trailTagQuery("dog", SourceKey.DANBOORU, context = null)
        assertEquals(QueryMode.Source(SourceKey.DANBOORU) to SortMode.NEWEST, local.mode to local.sort)
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
