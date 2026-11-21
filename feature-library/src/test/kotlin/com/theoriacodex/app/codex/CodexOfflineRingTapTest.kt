package com.theoriacodex.app.codex

import com.theoriacodex.app.media.CodexOfflineControls
import com.theoriacodex.app.media.CodexOfflineState
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexOfflineRingTapTest {
    private val calls = mutableListOf<String>()
    private val controls = object : CodexOfflineControls {
        override fun openSheet(codexId: String?) { calls += "sheet:$codexId" }
        override fun remove(codexId: String) { calls += "remove:$codexId" }
        override fun actOnRemoval(codexId: String) = Unit
        override fun commitRemovals() = Unit
    }
    private val topUp = { calls += "topUp"; Unit }

    @Test fun `the ring toggles a full copy off, shows a running one, and tops up or retries the rest`() {
        val cases = listOf(
            CodexOfflineState(1f) to "Remove offline copy",
            CodexOfflineState(0.5f, running = true) to "Show progress",
            CodexOfflineState(1f, failed = true) to "Retry",
            CodexOfflineState(0.5f) to "Download the rest",
        )
        cases.forEach { (state, label) ->
            val tap = codexOfflineRingTap("a", state, controls, topUp)
            assertEquals(label, tap.label)
            tap.run()
        }
        assertEquals(listOf("remove:a", "sheet:a", "topUp", "topUp"), calls)
    }

    @Test fun `without a top-up an incomplete ring opens the sheet`() {
        codexOfflineRingTap("a", CodexOfflineState(0.5f), controls, onTopUp = null).run()
        assertEquals(listOf("sheet:a"), calls)
    }
}
