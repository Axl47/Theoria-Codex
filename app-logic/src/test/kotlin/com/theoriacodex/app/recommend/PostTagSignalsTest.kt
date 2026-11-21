package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PostTagSignalsTest {
    @Test fun `likes count each tag once per Like across spellings`() {
        val counts = likedTagCounts(listOf(like("1", "Blue_Sky", "blue sky", "cat"), like("2", "cat")))
        assertEquals(LikedTagCounts(mapOf("blue sky" to 1, "cat" to 2), likes = 2), counts)
    }

    @Test fun `tags rank by how many Likes carry them, ties in the post's order`() {
        val counts = LikedTagCounts(mapOf("cat" to 5, "blue sky" to 2), likes = 20)
        val signals = postTagSignals(post("dog", "Blue_Sky", "cat", "tree"), counts)
        assertEquals(listOf("cat", "Blue_Sky", "dog", "tree"), signals.map(PostTagSignal::tag))
        assertEquals(listOf(5, 2, 0, 0), signals.map(PostTagSignal::likedCount))
    }

    @Test fun `a tag on nearly every Like ranks below a specific one`() {
        val counts = LikedTagCounts(mapOf("solo" to 10, "cat" to 4), likes = 10)
        assertEquals(listOf("cat", "solo"), postTagSignals(post("solo", "cat"), counts).map(PostTagSignal::tag))
    }

    @Test fun `raw tags stand in when a post has no canonical tags, deduplicated`() {
        val post = post().copy(rawTags = listOf("Cat", "cat", " "))
        assertEquals(listOf(PostTagSignal("Cat", 1)), postTagSignals(post, LikedTagCounts(mapOf("cat" to 1), 3)))
    }

    private fun like(id: String, vararg tags: String) =
        LikedPost("profile-main", PostId(SourceKey.GELBOORU, id), likedAtEpochMs = 0, tags = tags.toList())

    private fun post(vararg tags: String) = Post(
        id = PostId(SourceKey.GELBOORU, "p"), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags.toList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
