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
        fullRef: ImageRef? = null,
    ) = Post(
        id = PostId(SourceKey.DANBOORU, "1"),
        preview = ImageRef(url = preview, localPath = null, mime = null, thumbnailUrl = thumbnail),
        full = fullRef ?: full?.let { ImageRef(url = it, localPath = null, mime = null) },
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
    fun `booru file, sample and thumbnail URLs name the original's MD5 (ADR 0006)`() {
        assertEquals(MD5, post(full = "https://cdn.donmai.us/original/01/23/$MD5.jpg").fileHash)
        assertEquals(MD5, post(preview = "https://cdn.donmai.us/360x360/01/23/$MD5.webp").fileHash)
        assertEquals(MD5, post(preview = "https://img.gelbooru.com/samples/01/23/sample_$MD5.jpg").fileHash)
        assertEquals(MD5, post(thumbnail = "https://img.gelbooru.com/thumbnails/01/23/thumbnail_$MD5.jpg").fileHash)
        assertEquals(MD5, post(full = "https://files.yande.re/image/${MD5.uppercase()}/yande.re%20123%20tag.png").fileHash)
    }

    @Test
    fun `a reported hash wins and the original file comes before lighter renditions (ADR 0006)`() {
        assertEquals(OTHER, post(full = "https://cdn.donmai.us/original/01/23/$MD5.jpg", hash = OTHER).fileHash)
        assertEquals(MD5, post(full = "https://x.test/$MD5.jpg", preview = "https://x.test/$OTHER.jpg").fileHash)
    }

    @Test
    fun `tokens that are not a bounded 32-digit path segment never count (ADR 0006)`() {
        assertNull(post(full = "https://safebooru.org/images/1/${MD5}abcdef01.png").fileHash)
        assertNull(post(full = "https://ltn.hitomi.la/webp/1/23/$MD5$MD5.webp").fileHash)
        assertNull(post(full = "https://x.test/images/${MD5.drop(1)}.jpg").fileHash)
        assertNull(post(full = "https://x.test/image.jpg?token=$MD5").fileHash)
        assertNull(post(full = "https://$MD5.x.test/image.jpg").fileHash)
        assertNull(post(full = "https://rule34video.com/get_file/47/$MD5/3089000/3089604/3089604_720p.mp4/").fileHash)
        assertNull(post().fileHash)
    }

    @Test
    fun `only the file name and then its folder are searched (ADR 0006)`() {
        assertEquals(MD5, post(full = "https://x.test/images/$OTHER/$MD5.jpg").fileHash)
        assertEquals(OTHER, post(full = "https://x.test/images/$OTHER/sample.jpg").fileHash)
        assertNull(post(full = "https://x.test/$MD5/images/sample.jpg").fileHash)
        assertNull(post(full = "https://x.test/get_file/1/$MD5/123/123_720p.mp4/").fileHash)
    }

    @Test
    fun `the original address is searched first, then its fallbacks, lighter renditions and thumbnail (ADR 0006)`() {
        val roles = listOf(MD5, OTHER, THIRD, FOURTH)
        fun address(role: Int, from: Int) = if (role >= from) "https://x.test/${roles[role]}.jpg" else "https://x.test/plain.jpg"
        fun ref(from: Int) = ImageRef(
            url = address(0, from), localPath = null, mime = null,
            fallbackUrls = listOf(address(1, from)), progressiveUrls = listOf(address(2, from)), thumbnailUrl = address(3, from),
        )

        assertEquals(roles, (0..3).map { from -> post(fullRef = ref(from)).fileHash })
    }

    @Test
    fun `a hash only in a video rendition's address is never taken (ADR 0006)`() {
        val video = ImageRef(
            url = "https://x.test/video.mp4", localPath = null, mime = "video/mp4",
            videoVariants = listOf(VideoVariant("https://x.test/$MD5.mp4", height = 720)),
        )

        assertNull(post(fullRef = video).fileHash)
    }

    private companion object {
        const val MD5 = "0123456789abcdef0123456789abcdef"
        const val OTHER = "fedcba9876543210fedcba9876543210"
        const val THIRD = "00112233445566778899aabbccddeeff"
        const val FOURTH = "ffeeddccbbaa99887766554433221100"
    }
}
