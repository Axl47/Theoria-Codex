package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PortablePostSnapshotTest {
    @Test
    fun `a portable URL is plain http or https with a host and no user, query or fragment (ADR 0023)`() {
        val kept = listOf("https://example.test/a.jpg", "http://example.test/b.png")
        val dropped = listOf(
            "file:///sdcard/a.jpg", "content://media/external/1", "ftp://example.test/a.jpg", "/data/user/0/a.jpg",
            "https:///a.jpg", "https://user@example.test/a.jpg", "https://example.test/a.jpg?sig=1",
            "https://example.test/a.jpg#t=1",
        )

        assertEquals(kept, kept.mapNotNull(::portableMediaUrl))
        assertEquals(emptyList<String>(), dropped.mapNotNull(::portableMediaUrl))
    }

    @Test
    fun `a portable snapshot carries no stream addresses (ADR 0023)`() {
        val stream = MediaStream("https://example.test/live.m3u8", "Live")
        val video = ImageRef("https://example.test/v.mp4", null, "video/mp4", streams = listOf(stream))
        val post = Post(PostId(SourceKey.IWARA, "1"), video, video, pageUrl = null, width = 1, height = 1,
            canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null, media = listOf(video))

        val snapshot = portablePostSnapshot(post)

        assertEquals(emptyList<MediaStream>(), (listOf(snapshot.preview, snapshot.full!!) + snapshot.media).flatMap { it.streams })
    }
}
