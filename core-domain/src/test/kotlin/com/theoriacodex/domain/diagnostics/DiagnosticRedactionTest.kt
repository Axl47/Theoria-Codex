package com.theoriacodex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun `addresses keep only their host`() {
        assertEquals(
            "failed https://gelbooru.com/[redacted] (403)",
            DiagnosticRedaction.redact("failed https://user:pw@gelbooru.com/index.php?tags=secret_tag&id=12 (403)"),
        )
    }

    @Test
    fun `credentials are removed however they are written`() {
        listOf(
            "api_key=abc123", "\"refresh_token\": \"abc123\"", "Authorization: Bearer abc123.def456",
            "Cookie: abc123", "user_id=abc123", "password = abc123",
        ).forEach { line ->
            val redacted = DiagnosticRedaction.redact(line)
            assertFalse("$line became $redacted", "abc123" in redacted)
            assertTrue(redacted, "[redacted]" in redacted)
        }
    }

    @Test
    fun `personal values are removed and stack frames survive`() {
        assertEquals("[redacted]", DiagnosticRedaction.redact("someone@example.com"))
        assertEquals("[redacted]", DiagnosticRedaction.redact("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6"))
        assertEquals("[app file]", DiagnosticRedaction.redact("/storage/emulated/0/Download/TheoriaCodex/12345.png"))
        val frame = "at com.theoriacodex.app.viewer.ViewerVideoPlayer.onPlayerError(ViewerVideoPlayer.kt:230)"
        assertEquals(frame, DiagnosticRedaction.redact(frame))
    }
}
