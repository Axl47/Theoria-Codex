package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveToCodexSheetTest {
    @Test
    fun `save targets exclude the source Codex and preserve the remaining order`() {
        val source = codex("source")
        val firstTarget = codex("first")
        val secondTarget = codex("second")

        val targets = availableSaveTargetCodices(
            codices = listOf(firstTarget, source, secondTarget),
            excludedCodexIds = setOf(source.codexId),
        )

        assertEquals(listOf(firstTarget, secondTarget), targets)
    }

    private fun codex(id: String): Codex = Codex(
        codexId = id,
        name = id,
        createdAtEpochMs = 0L,
    )
}
