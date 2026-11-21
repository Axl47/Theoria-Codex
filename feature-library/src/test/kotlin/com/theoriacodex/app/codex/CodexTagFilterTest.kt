package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexTagFilterTest {
    private val fog = testPost(sourcePostId = "1", canonicalTags = listOf("fog", "Boats", "sea"))
    private val harbor = testPost(source = SourceKey.DANBOORU, sourcePostId = "2", canonicalTags = listOf("boats", "sea"))
    private val field = testPost(sourcePostId = "3", canonicalTags = listOf("field", "sea"))

    @Test fun `top tags count posts across sources and skip tags every post carries`() {
        val top = codexTopTags(listOf(fog, harbor, field))
        assertEquals(listOf("Boats" to 2, "field" to 1, "fog" to 1), top.map { it.tag to it.count })
        assertEquals(listOf("Boats", "field"), codexTopTags(listOf(fog, harbor, field), limit = 2).map { it.tag })
    }

    @Test fun `filtering by a tag key keeps posts carrying any spelling of it`() {
        val boats = codexTopTags(listOf(fog, harbor, field)).first()
        assertEquals(listOf(fog, harbor), listOf(fog, harbor, field).withCodexTag(boats.key))
        assertEquals(listOf(fog, harbor, field), listOf(fog, harbor, field).withCodexTag(null))
    }
}
