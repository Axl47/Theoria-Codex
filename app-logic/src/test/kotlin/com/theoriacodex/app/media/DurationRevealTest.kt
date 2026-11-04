package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationRevealTest {
    private val posts = (0 until 6).map(::video)
    private val known = MediaDurationState.Known(1_000L, MediaDurationProvenance.CONTAINER_PROBE)

    @Test
    fun `later decisions wait behind an undecided post`() {
        val reveal = advance(DurationReveal(), decided = setOf(0, 2, 3))

        assertEquals(listOf("0"), reveal.present(posts).ids())
    }

    @Test
    fun `a post stuck behind a full window is passed and joins the bottom when it arrives`() {
        val passed = advance(DurationReveal(), decided = setOf(0, 2, 3), window = 2)
        assertEquals(listOf("0", "1", "2", "3"), passed.present(posts).ids())

        val arrived = advance(passed, decided = setOf(0, 1, 2, 3), window = 2)
        assertEquals(listOf("0", "2", "3", "1"), arrived.present(posts).ids())

        val extended = advance(arrived, decided = setOf(0, 1, 2, 3, 4, 5), window = 2)
        assertEquals(listOf("0", "2", "3", "1", "4", "5"), extended.present(posts).ids())
    }

    @Test
    fun `a failed post that later gains a duration also joins the bottom`() {
        val failure = MediaDurationState.RetryableFailure(1L, MediaDurationFailureReason.TIMEOUT)
        val failed = advanceDurationReveal(
            DurationReveal(), posts,
            stateByPostId = posts.associate { post -> post.id to if (post.id.sourcePostId == "1") failure else known },
            knownDurationMsByPostId = posts.filterNot { it.id.sourcePostId == "1" }.associate { it.id to 1_000L },
        )
        val arrived = advance(failed, decided = posts.indices.toSet())

        assertEquals(listOf("0", "2", "3", "4", "5", "1"), arrived.present(posts).ids())
    }

    @Test
    fun `a replaced post list starts over`() {
        val reveal = advance(DurationReveal(), decided = posts.indices.toSet())
        val replacement = listOf(video(100), video(101))

        val next = advanceDurationReveal(reveal, replacement, emptyMap(), emptyMap())

        assertEquals(DurationReveal(), next)
    }

    private fun advance(reveal: DurationReveal, decided: Set<Int>, window: Int = 8): DurationReveal {
        val decidedPosts = posts.filterIndexed { index, _ -> index in decided }
        return advanceDurationReveal(
            reveal, posts,
            stateByPostId = decidedPosts.associate { it.id to known },
            knownDurationMsByPostId = decidedPosts.associate { it.id to 1_000L },
            window = window,
        )
    }

    private fun List<Post>.ids() = map { it.id.sourcePostId }

    private fun video(id: Int): Post {
        val media = ImageRef(url = "https://example.test/$id.mp4", localPath = null, mime = "video/mp4")
        return Post(
            id = PostId(SourceKey.GELBOORU, id.toString()),
            preview = media,
            full = media,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
