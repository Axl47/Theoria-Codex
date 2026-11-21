package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.SearchedTagKey
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagAffinityTest {
    private val source = SourceKey.GELBOORU

    @Test
    fun `a tag on most watched posts is universal, so specific liked tags lead`() {
        // The motivating case: "image" sits on 70% of Likes and on nearly everything watched.
        val likes = List(10) { index -> like(if (index < 7) listOf("image", "fox") else listOf("fox_ears")) }
        val watched = statistics(watched = 100, "image" to 95, "fox" to 10, "fox_ears" to 4)
        val inputs = tagAffinityInputs(likes, emptyList(), emptyList(), emptyList(), watched)

        val ranked = tagAffinities(post("image", "fox_ears", "fox", "tree"), inputs)

        assertEquals(listOf("fox", "fox_ears", "tree"), ranked.filterNot { it.universal }.map { it.tag })
        assertTrue(ranked.single { it.tag == "image" }.universal)
    }

    @Test
    fun `saved tags, saved searches and repeated searches outrank incidental Likes`() {
        val likes = listOf(like(listOf("sky")), like(listOf("sky")))
        val stats = LifetimeStatistics(searchesByTag = mapOf(SearchedTagKey(source, SearchTerm("Wolf")) to 2L))
        val saved = SavedSearchEntry("s", "Rivers", RecentSearchEntry(query("river"), "h", 0L), 0L)
        val inputs = tagAffinityInputs(likes, emptyList(), listOf("cat"), listOf(saved), stats)

        val order = tagAffinities(post("sky", "cat", "river", "wolf", "tree"), inputs).map { it.tag }

        assertEquals(listOf("cat", "river", "wolf", "sky", "tree"), order)
    }

    @Test
    fun `Codex posts count as interest like Likes do`() {
        val inputs = tagAffinityInputs(emptyList(), listOf(post("moth"), post("moth", "lamp")), emptyList(), emptyList(), LifetimeStatistics())

        assertEquals(listOf("moth", "lamp", "tree"), tagAffinities(post("tree", "lamp", "moth"), inputs).map { it.tag })
    }

    @Test
    fun `a tag on nearly every post of the feed at hand is universal there`() {
        val feed = List(10) { index -> post("animated", "tag$index") }
        val share = feedTagShare(feed)

        assertTrue(tagAffinities(post("animated", "tag1"), TagAffinityInputs.Empty, share).single { it.tag == "animated" }.universal)
        // Below ten posts a share says too little.
        assertEquals(emptyMap<String, Double>(), feedTagShare(feed.take(9)))
    }

    @Test
    fun `before enough watched posts, Likes alone mark a tag universal unless it was chosen explicitly`() {
        val likes = List(5) { like(listOf("solo", "cat")) }
        val inputs = tagAffinityInputs(likes, emptyList(), listOf("cat"), emptyList(), statistics(watched = 19, "solo" to 19))

        val ranked = tagAffinities(post("solo", "cat"), inputs).associateBy { it.tag }

        assertTrue(ranked.getValue("solo").universal)
        assertFalse(ranked.getValue("cat").universal)
    }

    private fun like(tags: List<String>) = LikedPost("profile", PostId(source, tags.joinToString("-")), 0L, tags)

    private fun statistics(watched: Long, vararg tags: Pair<String, Long>) = LifetimeStatistics(
        watchedBySource = mapOf(source to watched),
        watchedByTag = tags.associate { (tag, count) -> StatisticsTagKey(source, tag) to count },
    )

    private fun query(vararg tags: String) = Query(QueryMode.Source(source), tags.toList(), emptyList(), SortMode.NEWEST, null, null)

    private fun post(vararg tags: String) = Post(
        id = PostId(source, tags.joinToString("-")), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags.toList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
