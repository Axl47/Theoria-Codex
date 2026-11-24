package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistinctByFileTest {
    private fun post(source: SourceKey, id: String, hash: String? = null, picture: Long? = null) = Post(
        id = PostId(source, id),
        preview = ImageRef(url = null, localPath = null, mime = null),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        contentHash = hash,
        pictureHash = picture,
    )

    @Test
    fun `the first post for each file and each ID stays, in order (ADR 0006)`() {
        val posts = listOf(
            post(SourceKey.DANBOORU, "1", HASH_A),
            post(SourceKey.GELBOORU, "9", HASH_A),
            post(SourceKey.GELBOORU, "8", HASH_B),
            post(SourceKey.DANBOORU, "1", HASH_A),
            post(SourceKey.PIXIV, "7"),
            post(SourceKey.IWARA, "7"),
        )

        assertEquals(
            listOf(SourceKey.DANBOORU to "1", SourceKey.GELBOORU to "8", SourceKey.PIXIV to "7", SourceKey.IWARA to "7"),
            posts.distinctByFile().map { it.id.source to it.id.sourcePostId },
        )
    }

    @Test
    fun `a post rejected as a copy still registers what it passed (ADR 0006)`() {
        val posts = listOf(
            post(SourceKey.DANBOORU, "1", HASH_A),
            post(SourceKey.GELBOORU, "2", HASH_A),
            post(SourceKey.GELBOORU, "2", HASH_B),
            post(SourceKey.PIXIV, "3", picture = PICTURE),
            post(SourceKey.IWARA, "4", HASH_C, picture = PICTURE xor 0b1111L),
            post(SourceKey.RULE34XXX, "5", HASH_C),
        )

        assertEquals(listOf("1", "3"), posts.distinctByFile().map { it.id.sourcePostId })
    }

    @Test
    fun `already-shown copies stay and only new files are appended (ADR 0006)`() {
        val shown = listOf(
            post(SourceKey.DANBOORU, "1", HASH_A),
            post(SourceKey.GELBOORU, "2", HASH_A),
            post(SourceKey.PIXIV, "3", picture = PICTURE),
        )
        val incoming = listOf(
            post(SourceKey.RULE34XXX, "4", HASH_A),
            post(SourceKey.IWARA, "5", picture = PICTURE xor 0b1L),
            post(SourceKey.DANBOORU, "6", HASH_B),
            post(SourceKey.GELBOORU, "7", HASH_B),
        )

        assertEquals(listOf("1", "2", "3", "6"), shown.plusDistinctFiles(incoming).map { it.id.sourcePostId })
    }

    @Test
    fun `a stored copy that replaces a listed post keeps the listing's data (ADR 0006)`() {
        val listed = post(SourceKey.DANBOORU, "1", HASH_A, PICTURE)
            .copy(commentHint = CommentHint(count = 3), originUrl = "https://x.com/a/status/1")
        val stored = post(SourceKey.DANBOORU, "1")

        val kept = stored.keepingListingDataOf(listed)

        assertEquals(listOf(HASH_A, PICTURE, CommentHint(count = 3), "https://x.com/a/status/1"),
            listOf(kept.contentHash, kept.pictureHash, kept.commentHint, kept.originUrl))
        assertEquals(HASH_B, post(SourceKey.DANBOORU, "1", HASH_B).keepingListingDataOf(listed).contentHash)
        assertEquals(PICTURE xor 1L, post(SourceKey.DANBOORU, "1", picture = PICTURE xor 1L).keepingListingDataOf(listed).pictureHash)
    }

    @Test
    fun `only a 32-digit hex value is a file hash`() {
        assertEquals(HASH_A, contentHashOrNull(" ${HASH_A.uppercase()} "))
        assertNull(contentHashOrNull(HASH_A.dropLast(1)))
        assertNull(contentHashOrNull(HASH_A.dropLast(1) + "g"))
        assertNull(contentHashOrNull(""))
        assertNull(contentHashOrNull(null))
    }

    private companion object {
        const val HASH_A = "0123456789abcdef0123456789abcdef"
        const val HASH_B = "fedcba9876543210fedcba9876543210"
        const val HASH_C = "00112233445566778899aabbccddeeff"
        const val PICTURE = 0x0F0F_3C3C_5A5A_6969L
    }
}
