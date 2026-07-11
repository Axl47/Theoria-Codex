package com.theoriacodex.app.appshell

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingUriWorkflowTest {
    @Test
    fun `Pixiv callback wins classification precedence and restores`() {
        val handle = SavedStateHandle()
        val first = IncomingUriWorkflow(handle)

        val pending = first.accept(
            uri = "theoria://oauth/pixiv",
            isPixivAuthorizationCallback = true,
            isCodexImport = true,
        )

        assertEquals(IncomingUriKind.PIXIV_AUTH_CALLBACK, pending.kind)
        assertEquals(pending, IncomingUriWorkflow(handle).pending.value)
    }

    @Test
    fun `Codex and external content classifications remain distinct`() {
        val workflow = IncomingUriWorkflow(SavedStateHandle())

        assertEquals(
            IncomingUriKind.CODEX_IMPORT,
            workflow.accept("content://codex", false, true).kind,
        )
        assertEquals(
            IncomingUriKind.EXTERNAL_CONTENT,
            workflow.accept("https://example.test/post", false, false).kind,
        )
    }

    @Test
    fun `consume only clears the current handoff`() {
        val workflow = IncomingUriWorkflow(SavedStateHandle())
        val old = workflow.accept("https://example.test/old", false, false)
        val current = workflow.accept("https://example.test/current", false, false)

        workflow.consume(old)
        assertEquals(current, workflow.pending.value)

        workflow.consume(current)
        assertNull(workflow.pending.value)
    }
}
