package com.theoriacodex.app.post

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `a listing not learned yet is a copy of a known post with the same file (ADR 0180)`() {
        val index = FileMirrorIndex()
        val origin = filePost(SourceKey.DANBOORU, "1", HASH)
        index.learn(listOf(origin))

        assertTrue(index.isCopy(filePost(SourceKey.GELBOORU, "9", HASH), origin))
        assertFalse(index.isCopy(filePost(SourceKey.GELBOORU, "8", OTHER), origin))
    }

    @Test
    fun `a stored post without a hash is linked once a listing reports its hash`() {
        val index = FileMirrorIndex()
        val stored = testPost(SourceKey.GELBOORU, "9")
        val mirror = filePost(SourceKey.DANBOORU, "1", HASH)
        index.learn(listOf(stored, mirror))
        assertEquals(setOf(stored.id), index.withCopies(setOf(stored.id)))

        index.learn(listOf(stored.copy(contentHash = HASH)))

        assertEquals(setOf(stored.id, mirror.id), index.withCopies(setOf(stored.id)))
        assertEquals(1, index.links.value)
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
    fun `a listed repost of a stored picture joins it by picture hash, and copies of copies form one group`() {
        val index = FileMirrorIndex()
        val saved = testPost(SourceKey.DANBOORU, "1")
        index.learnPictures(mapOf(saved.id to PICTURE))
        val repost = testPost(SourceKey.PIXIV, "2").copy(pictureHash = PICTURE xor 0b111)
        val other = testPost(SourceKey.PIXIV, "3").copy(pictureHash = PICTURE xor 0xFF00)
        val mirror = filePost(SourceKey.GELBOORU, "4", HASH)

        index.learn(listOf(repost, other, repost.copy(id = mirror.id, contentHash = HASH)))

        assertEquals(setOf(saved.id, repost.id, mirror.id), index.withCopies(setOf(saved.id)))
        val unlearned = filePost(SourceKey.RULE34XXX, "5", HASH)
        assertEquals(setOf(unlearned.id, saved.id, repost.id, mirror.id), index.copiesOf(unlearned))
        assertEquals(setOf(other.id), index.withCopies(setOf(other.id)))
    }

    @Test
    fun `a set without copies comes back unchanged`() {
        val index = FileMirrorIndex()
        val ids = setOf(filePost(SourceKey.DANBOORU, "1", HASH).id)
        index.learn(listOf(filePost(SourceKey.DANBOORU, "1", HASH), testPost(SourceKey.PIXIV, "2")))

        assertSame(ids, index.withCopies(ids))
        assertEquals(setOf(testPost(SourceKey.PIXIV, "3").id), index.copiesOf(testPost(SourceKey.PIXIV, "3")))
    }

    private companion object {
        const val PICTURE = 0x0F0F_3C3C_5A5A_9669L
    }
}
