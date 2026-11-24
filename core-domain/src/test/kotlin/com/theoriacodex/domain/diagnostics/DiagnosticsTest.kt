package com.theoriacodex.domain.diagnostics

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTest {
    @After
    fun stopListening() = Diagnostics.install { _, _, _ -> }

    @Test
    fun `a sink that throws never breaks report (ADR 0002)`() {
        Diagnostics.install { _, _, _ -> error("the log is broken") }

        val failure = runCatching { Diagnostics.report("search", "Source failed", IllegalStateException("timeout")) }

        assertNull(failure.exceptionOrNull()?.message)
    }
}
