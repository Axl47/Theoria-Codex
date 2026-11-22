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
    fun `one search across several sources is not a repeated search`() {
        val stats = LifetimeStatistics(
            searchesByTag = mapOf(
                SearchedTagKey(SourceKey.GELBOORU, SearchTerm("wolf")) to 1L,
                SearchedTagKey(SourceKey.DANBOORU, SearchTerm("wolf")) to 1L,
            ),
        )

        assertFalse("wolf" in tagAffinityInputs(emptyList(), emptyList(), emptyList(), emptyList(), stats).explicit)
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

    @Test
    fun `with enough history a tag must be clearly more common in what was kept than in what was watched`() {
        // 40 kept posts: blush on half, as on half of what was watched; the character on a quarter, but rare when watching.
        val likes = List(40) { index ->
            like(listOfNotNull("blush".takeIf { index % 2 == 0 }, "miku".takeIf { index % 4 == 0 }, "tree"), "$index")
        }
        val watched = statistics(watched = 400, "blush" to 190, "miku" to 12, "tree" to 400)
        val inputs = tagAffinityInputs(likes, emptyList(), emptyList(), emptyList(), watched)

        val ranked = tagAffinities(post("blush", "tree", "miku"), inputs)

        assertEquals("miku", ranked.first().tag)
        assertEquals(listOf("miku"), ranked.filter { it.informative }.map { it.tag })
        assertFalse(ranked.single { it.tag == "blush" }.universal)
    }

    @Test
    fun `a tag too rare in what was watched to judge is still offered, after the proven ones`() {
        val likes = List(40) { index -> like(listOfNotNull("miku".takeIf { index % 4 == 0 }, "tree"), "$index") }
        // At these rates 40 kept posts would hold about 0.1 fox and 4 dog: missing dog says something, missing fox does not.
        val watched = statistics(watched = 400, "miku" to 12, "tree" to 400, "fox" to 1, "dog" to 40, "owl" to 2)
        val inputs = tagAffinityInputs(likes, emptyList(), emptyList(), emptyList(), watched)

        val offered = tagAffinities(post("dog", "owl", "fox", "miku"), inputs).filter { it.informative }.map { it.tag }

        assertEquals(listOf("miku", "fox", "owl"), offered)
    }

    @Test
    fun `text drawn on the image is never offered unless chosen, however specific`() {
        val likes = List(40) { index -> like(listOfNotNull("artist_name".takeIf { index % 4 == 0 }, "tree"), "$index") }
        // A handle on a platform the list never names is caught by its ending.
        val watched = statistics(watched = 400, "artist_name" to 12, "tree" to 400, "watermark" to 1, "bluesky_username" to 1)
        val inputs = tagAffinityInputs(likes, emptyList(), emptyList(), emptyList(), watched)
        val chosen = tagAffinityInputs(likes, emptyList(), listOf("watermark"), emptyList(), watched)

        val post = post("artist_name", "watermark", "bluesky_username")
        assertEquals(emptyList<String>(), tagAffinities(post, inputs).filter { it.informative }.map { it.tag })
        assertEquals(listOf("watermark"), tagAffinities(post, chosen).filter { it.informative }.map { it.tag })
    }

    @Test
    fun `lift compares each source with itself, and a chosen tag on most watched posts is still universal`() {
        val kept = List(30) { index -> like(listOf("tree", "moth"), "$index") }
        val watched = statistics(watched = 300, "tree" to 280, "moth" to 3)
        val inputs = tagAffinityInputs(kept, emptyList(), listOf("tree"), emptyList(), watched)

        assertTrue(inputs.lift(source, "moth")!! > 10)
        // The same tag on another source's post has no kept history there to compare.
        assertEquals(null, inputs.lift(SourceKey.DANBOORU, "moth"))
        assertTrue(tagAffinities(post("tree", "moth"), inputs).single { it.tag == "tree" }.universal)
    }

    private fun like(tags: List<String>, id: String = tags.joinToString("-")) = LikedPost("profile", PostId(source, id), 0L, tags)

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
