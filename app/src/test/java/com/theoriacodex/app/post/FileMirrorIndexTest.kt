package com.theoriacodex.app.post

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FileMirrorIndexTest {
    @Test
    fun `a copy listed by another site joins the stored post's set`() {
        val index = FileMirrorIndex()
        val liked = filePost(SourceKey.DANBOORU, "1", HASH)
        val mirror = filePost(SourceKey.GELBOORU, "9", HASH)
        index.learn(listOf(liked, filePost(SourceKey.GELBOORU, "8", OTHER)))

        assertEquals(setOf(liked.id), index.withCopies(setOf(liked.id)))
        index.learn(listOf(mirror))

        assertEquals(setOf(liked.id, mirror.id), index.withCopies(setOf(liked.id)))
        assertEquals(setOf(liked.id, mirror.id), index.copiesOf(mirror))
    }

    @Test
    fun `links change only when two known posts first share a file`() {
        val index = FileMirrorIndex()
        index.learn(listOf(filePost(SourceKey.DANBOORU, "1", HASH), testPost(SourceKey.PIXIV, "2")))
        assertEquals(0, index.links.value)

        index.learn(listOf(filePost(SourceKey.GELBOORU, "8", OTHER), filePost(SourceKey.DANBOORU, "1", HASH)))
        assertEquals(0, index.links.value)

        index.learn(listOf(filePost(SourceKey.RULE34XXX, "5", HASH)))
        assertEquals(1, index.links.value)
    }

    @Test
    fun `a set without copies comes back unchanged`() {
        val index = FileMirrorIndex()
        val ids = setOf(filePost(SourceKey.DANBOORU, "1", HASH).id)
        index.learn(listOf(filePost(SourceKey.DANBOORU, "1", HASH), testPost(SourceKey.PIXIV, "2")))

        assertSame(ids, index.withCopies(ids))
        assertEquals(setOf(testPost(SourceKey.PIXIV, "3").id), index.copiesOf(testPost(SourceKey.PIXIV, "3")))
    }
}

/** A booru-shaped post whose file URL carries [hash]. */
internal fun filePost(source: SourceKey, id: String, hash: String): Post = testPost(
    source = source,
    sourcePostId = id,
    full = ImageRef(url = "https://cdn.example/original/ab/cd/$hash.jpg", localPath = null, mime = "image/jpeg"),
)

internal const val HASH = "0123456789abcdef0123456789abcdef"
internal const val OTHER = "fedcba9876543210fedcba9876543210"
