package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistinctByFileTest {
    private fun post(source: SourceKey, id: String, hash: String? = null) = Post(
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
    )

    @Test
    fun `the first post for each file and each ID stays, in order`() {
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
    fun `a stored copy that replaces a listed post keeps the listing's file hash`() {
        val listed = post(SourceKey.DANBOORU, "1", HASH_A)
        val stored = post(SourceKey.DANBOORU, "1")

        assertEquals(HASH_A, stored.keepingListingDataOf(listed).contentHash)
        assertEquals(HASH_B, post(SourceKey.DANBOORU, "1", HASH_B).keepingListingDataOf(listed).contentHash)
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
    }
}
