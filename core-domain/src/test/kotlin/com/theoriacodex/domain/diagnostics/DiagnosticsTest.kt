package com.theoriacodex.domain.diagnostics

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @After
    fun stopListening() {
        Diagnostics.install { _, _, _ -> }
    }

    @Test
    fun `a sink that throws never breaks report (ADR 0002)`() {
        var recorded = false
        Diagnostics.install { _, _, _ ->
            recorded = true
            error("the log is broken")
        }

        val outcome = runCatching { Diagnostics.report("search", "Source failed", IllegalStateException("timeout")) }

        assertTrue("the sink was never called", recorded)
        assertTrue("report threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
    }
}
