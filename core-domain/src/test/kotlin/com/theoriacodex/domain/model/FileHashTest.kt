package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileHashTest {
    private fun post(
        full: String? = null,
        preview: String? = null,
        thumbnail: String? = null,
        hash: String? = null,
    ) = Post(
        id = PostId(SourceKey.DANBOORU, "1"),
        preview = ImageRef(url = preview, localPath = null, mime = null, thumbnailUrl = thumbnail),
        full = full?.let { ImageRef(url = it, localPath = null, mime = null) },
        pageUrl = "https://danbooru.donmai.us/posts/1",
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        contentHash = hash,
    )

    @Test
    fun `booru file, sample and thumbnail URLs name the original's MD5`() {
        assertEquals(MD5, post(full = "https://cdn.donmai.us/original/01/23/$MD5.jpg").fileHash)
        assertEquals(MD5, post(preview = "https://cdn.donmai.us/360x360/01/23/$MD5.webp").fileHash)
        assertEquals(MD5, post(preview = "https://img.gelbooru.com/samples/01/23/sample_$MD5.jpg").fileHash)
        assertEquals(MD5, post(thumbnail = "https://img.gelbooru.com/thumbnails/01/23/thumbnail_$MD5.jpg").fileHash)
        assertEquals(MD5, post(full = "https://files.yande.re/image/${MD5.uppercase()}/yande.re%20123%20tag.png").fileHash)
    }

    @Test
    fun `a reported hash wins and the original file comes before lighter renditions`() {
        assertEquals(OTHER, post(full = "https://cdn.donmai.us/original/01/23/$MD5.jpg", hash = OTHER).fileHash)
        assertEquals(MD5, post(full = "https://x.test/$MD5.jpg", preview = "https://x.test/$OTHER.jpg").fileHash)
    }

    @Test
    fun `tokens that are not a bounded 32-digit path segment never count`() {
        assertNull(post(full = "https://safebooru.org/images/1/${MD5}abcdef01.png").fileHash) // SHA-1 name
        assertNull(post(full = "https://ltn.hitomi.la/webp/1/23/$MD5$MD5.webp").fileHash) // SHA-256 name
        assertNull(post(full = "https://x.test/images/${MD5.drop(1)}.jpg").fileHash)
        assertNull(post(full = "https://x.test/image.jpg?token=$MD5").fileHash)
        assertNull(post(full = "https://$MD5.x.test/image.jpg").fileHash)
        assertNull(post().fileHash)
    }

    private companion object {
        const val MD5 = "0123456789abcdef0123456789abcdef"
        const val OTHER = "fedcba9876543210fedcba9876543210"
    }
}
