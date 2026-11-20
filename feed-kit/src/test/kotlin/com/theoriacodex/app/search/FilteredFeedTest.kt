package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.DurationFilterProjection
import com.theoriacodex.app.media.MediaDurationProvenance
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.ListFrame
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteredFeedTest {
    private val filters = SearchVisibilityFilters(animatedDurationRange = AnimatedDurationRange(maxBucket = 2))

    private fun filter(posts: List<Post>, durations: Map<PostId, Long>) =
        filterSearchResults(posts, filters, emptySet(), emptySet(), knownDurationMsByPostId = durations)

    /**
     * Lengths arrive in random order over a feed of videos and stills, as the length step presents them. After every
     * arrival the carried-over result must equal a whole pass over the presented list, and most arrivals (reveals and
     * waiting posts moving to the end) must carry over, filtering only the appended posts.
     */
    @Test fun `a carried-over result equals a whole pass after every arriving length`() {
        repeat(20) { seed ->
            val random = Random(seed)
            val posts = List(40) { index ->
                if (random.nextInt(3) == 0) testPost(sourcePostId = "s$index") else testPost(SourceKey.RULE34VIDEO, "v$index")
            }
            val projection = DurationFilterProjection()
            val states = posts.filter { it.id.source == SourceKey.RULE34VIDEO }.shuffled(random).map { post ->
                // About half inside the 0-10 s range the filter keeps.
                val ms = if (random.nextBoolean()) 4_000L else 30_000L
                mediaDurationKey(post) to MediaDurationState.Known(ms, MediaDurationProvenance.CONTAINER_PROBE)
            }
            // As the feed keys its pass: the presented list and the projection's key, and a key change refilters whole.
            var frame: ListFrame<Post, FilteredPosts>? = null
            var key: Any? = null
            var carried = 0
            var filtered = 0
            for (arrived in 0..states.size) {
                val metadata = projection.project(posts, states.take(arrived).toMap(), true)
                val presented = checkNotNull(metadata.presentedPosts)
                val known = metadata.knownDurationMsByPostId
                val previous = frame
                val value = when {
                    previous == null || key !== metadata.presentedDurationsKey -> FilteredPosts(filter(presented, known), known)
                    previous.list === presented -> previous.value
                    else -> extendFiltered(previous, presented, known) { appended ->
                        filtered += appended.size
                        filter(appended, known)
                    }?.also { carried++ } ?: FilteredPosts(filter(presented, known), known)
                }
                assertEquals("seed $seed, arrival $arrived", filter(presented, known), value.visible)
                frame = ListFrame(presented, emptyArray(), value)
                key = metadata.presentedDurationsKey
            }
            assertTrue("seed $seed carried $carried of ${states.size}", carried >= states.size / 2)
            assertTrue("seed $seed filtered $filtered posts", filtered < posts.size * 2)
        }
    }

    @Test fun `a shown post that moved or changed length is filtered with the whole list`() {
        val posts = List(4) { testPost(SourceKey.RULE34VIDEO, "v$it") }
        val durations = posts.associate { it.id to 4_000L }
        val previous = ListFrame(posts, emptyArray(), FilteredPosts(filter(posts, durations), durations))

        assertEquals(null, extendFiltered(previous, listOf(posts[0], posts[2], posts[3], posts[1]), durations) { it })
        assertEquals(null, extendFiltered(previous, posts, durations + (posts[2].id to 30_000L)) { it })
    }
}
