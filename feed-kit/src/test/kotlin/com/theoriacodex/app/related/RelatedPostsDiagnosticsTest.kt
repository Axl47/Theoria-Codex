package com.theoriacodex.app.related

import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedPostsDiagnosticsTest {
    @Test
    fun `records each event through the injected log`() {
        val lines = mutableListOf<String>()
        val owner = Any()
        val diagnostics = RelatedPostsDiagnostics("Search", owner, lines::add)

        diagnostics.record("owner created", RelatedPostsUiState.Idle)

        val label = "Search owner=${System.identityHashCode(owner).toString(16)}"
        assertEquals(listOf("$label owner created state=Idle source=null generation=null pages=0 selected=-1"), lines)
    }
}
