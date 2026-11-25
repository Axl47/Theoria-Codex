package com.theoriacodex.app.triage

import com.theoriacodex.app.recommend.TagAffinityInputs
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.vision.PictureLook
import com.theoriacodex.domain.vision.PictureTags
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TriageRulesTest {
    private fun swipe(x: Float = 0f, y: Float = 0f, vx: Float = 0f, vy: Float = 0f) =
        swipeDirection(x, y, vx, vy, commitDistance = 200f, flingVelocity = 1_000f)

    @Test fun `a drag commits in the direction of its axis once it passes the distance (ADR 0089)`() {
        assertEquals(SwipeDirection.Right, swipe(x = 220f, y = 40f))
        assertEquals(SwipeDirection.Left, swipe(x = -220f))
        assertEquals(SwipeDirection.Up, swipe(y = -220f, x = 60f))
        assertEquals(SwipeDirection.Down, swipe(y = 220f))
    }

    @Test fun `a short slow drag springs back (ADR 0089)`() {
        assertNull(swipe(x = 150f, y = 30f, vx = 300f))
    }

    @Test fun `a fling commits a short drag (ADR 0089)`() {
        assertEquals(SwipeDirection.Left, swipe(x = -40f, vx = -1_800f))
        assertEquals(SwipeDirection.Up, swipe(y = -30f, vy = -1_500f, vx = 200f))
    }

    @Test fun `a fling against the pull cancels it (ADR 0089)`() {
        assertNull(swipe(x = 260f, vx = -1_500f))
    }

    @Test fun `a fast flick picks its axis by speed as well as travel (ADR 0089)`() {
        assertEquals(SwipeDirection.Right, swipe(x = 30f, y = 40f, vx = 2_000f))
        assertEquals(SwipeDirection.Down, swipe(x = 60f, y = 10f, vy = 1_500f))
    }

    @Test fun `a diagonal drag follows the axis it travelled further on (ADR 0089)`() {
        assertEquals(SwipeDirection.Up, swipe(x = 150f, y = -230f))
        assertEquals(SwipeDirection.Right, swipe(x = 230f, y = -150f))
    }

    private val counts = TagAffinityInputs(
        likedCounts = mapOf("cat" to 6, "sky" to 3), likes = 20, interest = mapOf("cat" to 6.0, "sky" to 3.0),
    )

    @Test fun `hide candidates start with the tags the Likes carry least, in the post's order among equals (ADR 0089)`() {
        assertEquals(listOf("tree", "dog", "sky"), hideCandidates(post("cat", "sky", "tree", "dog"), counts, emptyList()))
    }

    @Test fun `among equals a content tag beats artist, character, series, group, type, language, meta and rating tags (ADR 0089)`() {
        val namespaces = mapOf(
            "hero" to "character", "series" to "copyright", "remake" to "parody", "crew" to "group", "kind" to "category",
            "jp" to "language", "highres" to "meta", "rating_e" to "rating", "wolf" to "general", "tree" to "general",
        )
        val tagged = post("artist_name", "hero", "series", "remake", "crew", "kind", "jp", "highres", "rating_e", "wolf", "tree").copy(
            taxonomy = listOf(PostTaxonomyTerm("artist_name", SearchFacet.ARTIST)) +
                namespaces.map { (tag, namespace) -> PostTaxonomyTerm(tag, SearchFacet.TAG, namespace) },
        )
        assertEquals(
            listOf("wolf", "tree", "artist_name", "hero", "series", "remake", "crew", "kind", "jp", "highres", "rating_e"),
            hideCandidates(tagged, counts, emptyList(), limit = 20),
        )
    }

    @Test fun `among equals text on the image comes after content, even where the source gives no tag types (ADR 0089)`() {
        assertEquals(listOf("tree", "watermark", "bluesky_username"), hideCandidates(post("watermark", "bluesky_username", "tree"), counts, emptyList()))
    }

    @Test fun `hide candidates skip query tags and hidden tags in any spelling`() {
        val candidates = hideCandidates(post("cat", "Blue_Sky", "tree", "dog"), counts, listOf("blue sky", "DOG"))
        assertEquals(listOf("tree", "cat"), candidates)
    }

    @Test fun `a universal tag is never offered, however little it is liked (ADR 0089)`() {
        val watched = counts.copy(
            watchedBySource = mapOf(SourceKey.GELBOORU to 40L),
            watchedByTag = mapOf(SourceKey.GELBOORU to mapOf("image" to 38L, "tree" to 2L)),
        )
        assertEquals(listOf("tree", "sky"), hideCandidates(post("image", "sky", "tree"), watched, emptyList()))
    }

    @Test fun `a post with nothing left to hide has no candidates`() {
        assertEquals(emptyList<String>(), hideCandidates(post("cat"), counts, listOf("cat")))
    }

    private class Seen(private val seen: List<PredictedTag>?, vocabulary: List<String> = WORDS) : PictureTags {
        override val ready = MutableStateFlow(true)
        override val library = MutableStateFlow(PredictedTagIndex(baseRates = vocabulary.associateWith { 0.01 }))
        override fun known(id: PostId) = seen
        override suspend fun look(post: Post) = PictureLook.Blind
    }

    private fun seen(tag: String, score: Float, kind: PredictedTag.Kind = PredictedTag.Kind.GENERAL) =
        Seen(listOf(PredictedTag(tag, score, kind)))

    @Test fun `a predicted tag hides a sparsely tagged post when confident enough, through any booru source's hidden tags (ADR 0089)`() {
        val untagged = post().copy(id = PostId(SourceKey.NHENTAI, "n"))
        val hidden = mapOf(SourceKey.DANBOORU to setOf("furry"), SourceKey.HITOMI to setOf("tail"))

        assertTrue(untagged.hiddenBy(hidden, seen("furry", 0.4f)))
        assertFalse(untagged.hiddenBy(hidden, seen("furry", 0.3f)))
        assertFalse(untagged.hiddenBy(hidden, seen("furry", 0.45f, PredictedTag.Kind.CHARACTER)))
        assertFalse(untagged.hiddenBy(hidden, seen("tail", 0.9f)))
        assertTrue(untagged.copy(id = PostId(SourceKey.HITOMI, "h")).hiddenBy(hidden, seen("tail", 0.9f)))
        assertFalse(untagged.hiddenBy(hidden, Seen(seen = null)))
    }

    @Test fun `a sparse Hitomi post's predicted tag meets the hidden tags of every booru source (ADR 0089)`() {
        val sparse = post().copy(id = PostId(SourceKey.HITOMI, "h"))
        val custom = requireNotNull(SourceKey.parse("custom_booru_example"))
        val booruSources = listOf(SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.RULE34XXX, SourceKey.PIXIV, custom)

        booruSources.forEach { booru ->
            assertTrue(booru.id, sparse.hiddenBy(mapOf(booru to setOf("furry")), seen("furry", 0.4f)))
        }
        assertFalse(sparse.hiddenBy(mapOf(SourceKey.NHENTAI to setOf("furry")), seen("furry", 0.4f)))
    }

    @Test fun `a post its provider tagged well is left to its own tags, whatever its picture shows (ADR 0089)`() {
        val hidden = mapOf(SourceKey.GELBOORU to setOf("furry"))
        assertFalse(post(*WORDS.toTypedArray()).hiddenBy(hidden, seen("furry", 0.9f)))
        assertTrue(post(*WORDS.drop(1).toTypedArray()).hiddenBy(hidden, seen("furry", 0.9f)))
        assertTrue(post(*WORDS.drop(1).toTypedArray()).copy(rawTags = WORDS.drop(1)).hiddenBy(hidden, seen("furry", 0.9f)))
        assertTrue(post(*Array(10) { "other $it" }).hiddenBy(hidden, seen("furry", 0.9f)))
        assertTrue(post(*WORDS.toTypedArray()).hiddenBy(hidden, Seen(listOf(PredictedTag("furry", 0.9f, PredictedTag.Kind.GENERAL)), emptyList())))
    }

    @Test fun `a provider tag meets the hidden tags written in its vocabulary (ADR 0089)`() {
        val booru = post("Furry")
        assertTrue(booru.hiddenBy(mapOf(SourceKey.GELBOORU to setOf("furry"))))
        assertTrue(booru.hiddenBy(mapOf(SourceKey.DANBOORU to setOf("furry"))))
        assertFalse(booru.hiddenBy(mapOf(SourceKey.HITOMI to setOf("furry"))))
        val rawOnly = booru.copy(canonicalTags = emptyList(), rawTags = listOf("Furry"))
        assertTrue(rawOnly.hiddenBy(mapOf(SourceKey.DANBOORU to setOf("furry"))))
        assertFalse(rawOnly.hiddenBy(mapOf(SourceKey.DANBOORU to setOf("wolf"))))
        val hitomi = booru.copy(id = PostId(SourceKey.HITOMI, "x"))
        assertFalse(hitomi.hiddenBy(mapOf(SourceKey.DANBOORU to setOf("furry"))))
        assertTrue(hitomi.hiddenBy(mapOf(SourceKey.HITOMI to setOf("furry"))))
    }

    private fun post(vararg tags: String) = Post(
        id = PostId(SourceKey.GELBOORU, "p"), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags.toList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )

    private companion object {
        val WORDS = List(10) { "word $it" }
    }
}
