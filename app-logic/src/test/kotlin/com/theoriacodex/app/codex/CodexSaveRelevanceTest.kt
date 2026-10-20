package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexSaveRelevanceTest {
    @Test fun `scores count matching tags per unique saved post across providers`() {
        val incoming = post("incoming", listOf("Blue Hair", "cat", "cat", " "))
        val relevance = CodexSaveRelevance(listOf(incoming, incoming.copy(id = PostId(SourceKey.GELBOORU, "batch"))))
        val one = post("one", listOf("blue_hair", "CAT", "cat"))
        val two = post("two", listOf("cat"))
        assertEquals(3L, relevance.score(listOf(one, two, one)))
        assertEquals(2L, relevance.score(listOf(one.copy(id = PostId(SourceKey.PIXIV, "one")))))
        assertEquals(0L, relevance.score(listOf(post("other", listOf("dog")))))
        assertEquals(0L, relevance.score(emptyList()))
    }

    @Test fun `missing tags have no relevance and raw tags provide a fallback`() {
        assertFalse(CodexSaveRelevance(listOf(post("empty", emptyList()))).hasTags)
        val incoming = post("incoming", emptyList()).copy(rawTags = listOf("cat"))
        assertEquals(1L, CodexSaveRelevance(listOf(incoming)).score(listOf(post("saved", listOf("cat")))))
    }

    private fun post(id: String, tags: List<String>) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags, rawTags = tags,
        authorName = null, createdAtEpochMs = null,
    )
}
