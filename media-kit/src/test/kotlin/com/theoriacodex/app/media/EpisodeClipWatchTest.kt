package com.theoriacodex.app.media

import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeClipWatchTest {
    @Test
    fun `a clip that fails before it is ever seen running is still reported once (ADR 0223)`() {
        val watch = EpisodeClipWatch(GRACE_MS)
        watch.expect(listOf("a"), retried = false, nowMs = 0L)
        val failed = queue(clip("a", PostDownloadStatus.FAILED))

        assertTrue(watch.hasWork(failed))
        assertEquals(listOf("a"), watch.finished(failed, 1L).map { it.request.marker })
        assertEquals(emptyList<PostDownloadItem>(), watch.finished(failed, 2L))
        assertTrue(watch.done(failed, 2L))
    }

    @Test
    fun `a retried clip is reported only after it has run again (ADR 0223)`() {
        val watch = EpisodeClipWatch(GRACE_MS)
        watch.expect(listOf("a"), retried = true, nowMs = 0L)

        assertEquals(emptyList<PostDownloadItem>(), watch.finished(queue(clip("a", PostDownloadStatus.FAILED)), 1L))
        assertEquals(emptyList<PostDownloadItem>(), watch.finished(queue(clip("a", PostDownloadStatus.QUEUED)), 2L))
        assertEquals(listOf("a"), watch.finished(queue(clip("a", PostDownloadStatus.SAVED)), 3L).map { it.request.marker })
    }

    @Test
    fun `the watch stays open for a clip that has not reached the queue until its grace ends (ADR 0223)`() {
        val watch = EpisodeClipWatch(GRACE_MS)
        watch.expect(listOf("a", "b"), retried = false, nowMs = 0L)
        val onlyA = queue(clip("a", PostDownloadStatus.FAILED))
        watch.finished(onlyA, 1L)

        assertFalse(watch.done(onlyA, 1L))
        assertTrue(watch.done(onlyA, GRACE_MS))
    }

    private fun queue(vararg items: PostDownloadItem) = listOf(PostDownloadBatch(1L, "Clip", items.toList()))

    private fun clip(marker: String, status: PostDownloadStatus): PostDownloadItem {
        val media = ImageRef("https://example.test/e.jpg", null, "image/jpeg")
        val post = Post(PostId(SourceKey.HITOMI, marker), media, media, listOf(media), null, null, null,
            emptyList(), emptyList(), null, null, title = marker)
        val request = EpisodeClipRequest(EpisodeClipRange(0L, 30_000L), MediaStream("https://example.test/e.m3u8", "Main"))
        return PostDownloadItem(marker.hashCode().toLong() and Long.MAX_VALUE, PostDownloadRequest(post, marker = marker, clip = request), status)
    }
}

private const val GRACE_MS = 10_000L
