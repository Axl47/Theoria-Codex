package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerResolutionState
import com.theoriacodex.app.viewer.state.toViewerPageState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPrefetchPlannerTest {
    @Test
    fun `middle media schedules nearest right neighbors before nearest left neighbors`() {
        val pages = pages(mediaCounts = listOf(2, 3, 2))
        val flattened = pages.flatMap { page -> page.media.map { media -> media.key } }

        assertEquals(
            listOf(flattened[4], flattened[5], flattened[6], flattened[2], flattened[1], flattened[0]),
            planAdjacentViewerPrefetch(pages, current = flattened[3]),
        )
    }

    @Test
    fun `start and end windows stay bounded without including current media`() {
        val pages = pages(mediaCounts = listOf(2, 3, 2))
        val flattened = pages.flatMap { page -> page.media.map { media -> media.key } }

        assertEquals(flattened.slice(1..3), planAdjacentViewerPrefetch(pages, flattened.first()))
        assertEquals(
            listOf(flattened[5], flattened[4], flattened[3]),
            planAdjacentViewerPrefetch(pages, flattened.last()),
        )
        assertTrue(planAdjacentViewerPrefetch(pages, flattened.first()).none { it == flattened.first() })
    }

    @Test
    fun `unknown current identity produces no speculative work`() {
        val pages = pages(mediaCounts = listOf(2, 2))
        val unknown = ViewerMediaKey(PostId(SourceKey.PIXIV, "missing"), 0)

        assertTrue(planAdjacentViewerPrefetch(pages, current = unknown).isEmpty())
        assertTrue(planAdjacentViewerPrefetch(pages, current = null).isEmpty())
    }

    @Test
    fun `duplicate canonical media keys are returned only once`() {
        val original = pages(mediaCounts = listOf(2, 2))
        val repeatedPost = original.first().copy(
            media = original.first().media + original.last().media + original.last().media,
        )
        val current = repeatedPost.media.first().key

        val planned = planAdjacentViewerPrefetch(
            pages = listOf(repeatedPost),
            current = current,
            leftCount = 6,
            rightCount = 6,
        )

        assertEquals(planned.distinct(), planned)
        assertTrue(current !in planned)
    }

    private fun pages(mediaCounts: List<Int>): List<ViewerPageState> {
        return mediaCounts.mapIndexed { postIndex, mediaCount ->
            val media = (0 until mediaCount).map { mediaIndex ->
                ImageRef(
                    url = "https://media.example/$postIndex-$mediaIndex.jpg",
                    localPath = null,
                    mime = "image/jpeg",
                )
            }
            Post(
                id = PostId(SourceKey.PIXIV, "post-$postIndex"),
                preview = media.first(),
                full = media.first(),
                media = media,
                pageUrl = "https://example.test/post-$postIndex",
                width = 100,
                height = 100,
                canonicalTags = emptyList(),
                rawTags = emptyList(),
                authorName = null,
                createdAtEpochMs = null,
            ).toViewerPageState(resolution = ViewerResolutionState())
        }
    }
}
