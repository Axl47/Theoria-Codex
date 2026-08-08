package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexEditSelectionTest {
    @Test
    fun `selection changes only while edit mode is active`() {
        val first = postId("1")
        val inactive = CodexEditSelection()

        assertEquals(inactive, inactive.toggle(first))

        val selected = inactive.begin().toggle(first)
        assertTrue(selected.active)
        assertEquals(setOf(first), selected.selectedPostIds)
        assertEquals(emptySet<PostId>(), selected.toggle(first).selectedPostIds)
    }

    @Test
    fun `reconciliation drops posts that are no longer in the codex`() {
        val first = postId("1")
        val second = postId("2")
        val selected = CodexEditSelection()
            .begin()
            .toggle(first)
            .toggle(second)

        val reconciled = selected.retainAvailable(setOf(second))

        assertTrue(reconciled.active)
        assertEquals(setOf(second), reconciled.selectedPostIds)
    }

    @Test
    fun `exit clears mode and selection and begin starts clean`() {
        val selected = CodexEditSelection().begin().toggle(postId("1"))

        val exited = selected.exit()

        assertFalse(exited.active)
        assertTrue(exited.selectedPostIds.isEmpty())
        assertEquals(CodexEditSelection(active = true), selected.begin())
    }

    private fun postId(value: String): PostId = PostId(SourceKey.PIXIV, value)
}
