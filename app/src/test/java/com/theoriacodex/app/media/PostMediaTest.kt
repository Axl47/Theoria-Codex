package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMediaTest {
    @Test
    fun `media kind detects videos from mime and extension`() {
        assertEquals(PostMediaKind.VIDEO, mediaKind(mime = "video/mp4", location = null))
        assertEquals(PostMediaKind.VIDEO, mediaKind(mime = null, location = "https://x/y/file.webm?z=1"))
        assertEquals(PostMediaKind.IMAGE, mediaKind(mime = null, location = "https://x/y/file.jpg"))
        assertEquals(PostMediaKind.UNKNOWN, mediaKind(mime = null, location = "https://x/y/file"))
    }

    @Test
    fun `animated detection includes video and gif`() {
        val videoPost = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.mp4", localPath = null, mime = "video/mp4"),
        )
        val gifPost = samplePost(
            source = SourceKey.AIBOORU,
            preview = ImageRef(url = "https://aibooru.online/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://aibooru.online/file.gif", localPath = null, mime = "image/gif"),
        )
        val staticPost = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.jpg", localPath = null, mime = "image/jpeg"),
        )

        assertTrue(isAnimatedPost(videoPost))
        assertTrue(isAnimatedPost(gifPost))
        assertFalse(isAnimatedPost(staticPost))
    }

    @Test
    fun `pixiv ugoira detection requires pixiv source`() {
        val pixivUgoira = samplePost(
            source = SourceKey.PIXIV,
            preview = ImageRef(url = "https://i.pximg.net/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://i.pximg.net/file.zip", localPath = null, mime = PIXIV_UGOIRA_MIME),
        )
        val nonPixiv = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.mp4", localPath = null, mime = PIXIV_UGOIRA_MIME),
        )

        assertTrue(isPixivUgoiraPost(pixivUgoira))
        assertFalse(isPixivUgoiraPost(nonPixiv))
    }

    @Test
    fun `iwara posts are treated as animated even before resolve`() {
        val unresolved = samplePost(
            source = SourceKey.IWARA,
            preview = ImageRef(url = "https://i.iwara.tv/thumb.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
        )

        assertTrue(isAnimatedPost(unresolved))
    }

    private fun samplePost(
        source: SourceKey,
        preview: ImageRef,
        full: ImageRef?,
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = preview,
            full = full,
            media = full?.let { listOf(it) }.orEmpty(),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
