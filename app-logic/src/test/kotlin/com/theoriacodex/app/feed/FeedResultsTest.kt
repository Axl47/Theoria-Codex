package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedResultsTest {
    private fun post(source: SourceKey, id: String, hash: String? = null, title: String? = null) = Post(
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
        title = title,
        contentHash = hash,
    )

    @Test
    fun `a later page refreshes shown posts, adds new ones and leaves out copies of shown files (ADR 0069)`() {
        val shown = listOf(post(SourceKey.DANBOORU, "1", HASH), post(SourceKey.PIXIV, "2"), post(SourceKey.PIXIV, "3"))
        val incoming = listOf(
            post(SourceKey.GELBOORU, "9", HASH),
            post(SourceKey.PIXIV, "2", title = "fresher"),
            post(SourceKey.GELBOORU, "8"),
        )

        val merged = mergeFeedPosts(shown, incoming)

        assertEquals(listOf("1", "2", "3", "8"), merged.map { it.id.sourcePostId })
        assertEquals("fresher", merged[1].title)
    }

    @Test
    fun `posts with the same provider id on different sources are different posts (ADR 0069)`() {
        val shown = listOf(post(SourceKey.PIXIV, "7", title = "pixiv"), post(SourceKey.DANBOORU, "8"))
        val incoming = listOf(post(SourceKey.GELBOORU, "7", title = "gelbooru"), post(SourceKey.PIXIV, "9"))

        val merged = mergeFeedPosts(shown, incoming)

        assertEquals(
            listOf(PostId(SourceKey.PIXIV, "7"), PostId(SourceKey.DANBOORU, "8"), PostId(SourceKey.GELBOORU, "7"), PostId(SourceKey.PIXIV, "9")),
            merged.map { it.id },
        )
        assertEquals(listOf("pixiv", null, "gelbooru", null), merged.map { it.title })
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef"
    }
}
