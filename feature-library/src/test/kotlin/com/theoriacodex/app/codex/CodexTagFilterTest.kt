package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexTagFilterTest {
    private val fog = testPost(sourcePostId = "1", canonicalTags = listOf("fog", "Boats", "sea"))
    private val harbor = testPost(source = SourceKey.DANBOORU, sourcePostId = "2", canonicalTags = listOf("boats", "sea"))
    private val field = testPost(sourcePostId = "3", canonicalTags = listOf("field", "sea"))
    private val posts = listOf(fog, harbor, field)

    @Test fun `tags count posts across sources and skip tags every post carries`() {
        assertEquals(listOf("Boats" to 2, "field" to 1, "fog" to 1), codexTags(posts).map { it.tag to it.count })
    }

    @Test fun `filtering keeps posts carrying every chosen tag in any spelling`() {
        val keys = codexTags(posts).associate { it.tag to it.key }
        assertEquals(listOf(fog, harbor), posts.withCodexTags(listOf(keys.getValue("Boats"))))
        assertEquals(listOf(fog), posts.withCodexTags(listOf(keys.getValue("Boats"), keys.getValue("fog"))))
        assertEquals(posts, posts.withCodexTags(emptyList()))
    }

    @Test fun `a site search starts from the chosen tags on the source that has the most of them`() {
        val keys = codexTags(posts).filter { it.tag in setOf("Boats", "fog") }.map { it.key }
        val pixiv = codexSearchTerms(posts, SourceKey.PIXIV)
        val danbooru = codexSearchTerms(posts, SourceKey.DANBOORU)
        assertEquals(listOf("fog", "Boats"), pixiv.seededTerms(keys.reversed()).map(SearchTerm::value))
        val sources = listOf(SourceKey.DANBOORU, SourceKey.PIXIV)
        assertEquals(SourceKey.PIXIV, seededSource(sources, mapOf(SourceKey.PIXIV to pixiv, SourceKey.DANBOORU to danbooru), keys))
        assertNull(seededSource(sources, mapOf(SourceKey.PIXIV to pixiv), emptyList()))
    }
}
