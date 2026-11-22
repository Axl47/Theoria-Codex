package com.theoriacodex.app.triage

import com.theoriacodex.app.recommend.TagAffinityInputs
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PredictedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TriageRulesTest {
    private fun swipe(x: Float = 0f, y: Float = 0f, vx: Float = 0f, vy: Float = 0f) =
        swipeDirection(x, y, vx, vy, commitDistance = 200f, flingVelocity = 1_000f)

    @Test fun `a drag commits in the direction of its axis once it passes the distance`() {
        assertEquals(SwipeDirection.Right, swipe(x = 220f, y = 40f))
        assertEquals(SwipeDirection.Left, swipe(x = -220f))
        assertEquals(SwipeDirection.Up, swipe(y = -220f, x = 60f))
        assertEquals(SwipeDirection.Down, swipe(y = 220f))
    }

    @Test fun `a short slow drag springs back`() {
        assertNull(swipe(x = 150f, y = 30f, vx = 300f))
    }

    @Test fun `a fling commits a short drag`() {
        assertEquals(SwipeDirection.Left, swipe(x = -40f, vx = -1_800f))
        assertEquals(SwipeDirection.Up, swipe(y = -30f, vy = -1_500f, vx = 200f))
    }

    @Test fun `a fling against the pull cancels it`() {
        assertNull(swipe(x = 260f, vx = -1_500f))
    }

    @Test fun `a diagonal drag follows the axis it travelled further on`() {
        assertEquals(SwipeDirection.Up, swipe(x = 150f, y = -230f))
        assertEquals(SwipeDirection.Right, swipe(x = 230f, y = -150f))
    }

    private val counts = TagAffinityInputs(
        likedCounts = mapOf("cat" to 6, "sky" to 3), likes = 20, interest = mapOf("cat" to 6.0, "sky" to 3.0),
    )

    @Test fun `hide candidates start with the tags the Likes carry least, in the post's order among equals`() {
        assertEquals(listOf("tree", "dog", "sky"), hideCandidates(post("cat", "sky", "tree", "dog"), counts, emptyList()))
    }

    @Test fun `among equals a content tag beats artist, meta and rating tags`() {
        val tagged = post("artist_name", "highres", "rating_e", "wolf").copy(
            taxonomy = listOf(
                PostTaxonomyTerm("artist_name", SearchFacet.ARTIST),
                PostTaxonomyTerm("highres", SearchFacet.TAG, "meta"),
                PostTaxonomyTerm("rating_e", SearchFacet.TAG, "rating"),
                PostTaxonomyTerm("wolf", SearchFacet.TAG, "general"),
            ),
        )
        assertEquals(listOf("wolf", "artist_name", "highres"), hideCandidates(tagged, counts, emptyList()))
    }

    @Test fun `among equals text on the image comes after content, even where the source gives no tag types`() {
        assertEquals(listOf("tree", "watermark", "bluesky_username"), hideCandidates(post("watermark", "bluesky_username", "tree"), counts, emptyList()))
    }

    @Test fun `hide candidates skip query tags and hidden tags in any spelling`() {
        val candidates = hideCandidates(post("cat", "Blue_Sky", "tree", "dog"), counts, listOf("blue sky", "DOG"))
        assertEquals(listOf("tree", "cat"), candidates)
    }

    @Test fun `a universal tag is never offered, however little it is liked`() {
        val watched = counts.copy(
            watchedBySource = mapOf(SourceKey.GELBOORU to 40L),
            watchedByTag = mapOf(SourceKey.GELBOORU to mapOf("image" to 38L, "tree" to 2L)),
        )
        assertEquals(listOf("tree", "sky"), hideCandidates(post("image", "sky", "tree"), watched, emptyList()))
    }

    @Test fun `a post with nothing left to hide has no candidates`() {
        assertEquals(emptyList<String>(), hideCandidates(post("cat"), counts, listOf("cat")))
    }

    @Test fun `a predicted tag hides an untagged post when confident enough, through any booru source's hidden tags`() {
        val untagged = post().copy(id = PostId(SourceKey.NHENTAI, "n"))
        val hidden = mapOf(SourceKey.DANBOORU to setOf("furry"), SourceKey.HITOMI to setOf("tail"))
        fun predicted(tag: String, score: Float, kind: PredictedTag.Kind = PredictedTag.Kind.GENERAL) = listOf(PredictedTag(tag, score, kind))

        assertTrue(untagged.hiddenBy(hidden, predicted("furry", 0.4f)))
        assertFalse(untagged.hiddenBy(hidden, predicted("furry", 0.3f)))
        assertFalse(untagged.hiddenBy(hidden, predicted("furry", 0.45f, PredictedTag.Kind.CHARACTER)))
        // Hitomi does not share booru vocabulary, so only its own posts' predictions meet its hidden tags.
        assertFalse(untagged.hiddenBy(hidden, predicted("tail", 0.9f)))
        assertTrue(untagged.copy(id = PostId(SourceKey.HITOMI, "h")).hiddenBy(hidden, predicted("tail", 0.9f)))
        assertFalse(untagged.hiddenBy(hidden, predicted = null))
    }

    @Test fun `a provider tag still hides only through its own source's hidden tags`() {
        val tagged = post("furry")
        assertTrue(tagged.hiddenBy(mapOf(SourceKey.GELBOORU to setOf("furry")), predicted = null))
        assertFalse(tagged.hiddenBy(mapOf(SourceKey.DANBOORU to setOf("furry")), predicted = null))
    }

    private fun post(vararg tags: String) = Post(
        id = PostId(SourceKey.GELBOORU, "p"), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags.toList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
