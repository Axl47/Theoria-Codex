package com.theoriacodex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun `addresses keep only their host (ADR 0002)`() {
        assertEquals(
            "failed https://gelbooru.com/[redacted] (403)",
            DiagnosticRedaction.redact("failed https://user:pw@gelbooru.com/index.php?tags=secret_tag&id=12 (403)"),
        )
    }

    @Test
    fun `credentials are removed however they are written (ADR 0002)`() {
        listOf(
            "api_key=abc123", "\"refresh_token\": \"abc123\"", "Authorization: Bearer abc123.def456",
            "Cookie: abc123", "user_id=abc123", "password = abc123", "Bearer abc123def456",
        ).forEach { line ->
            val redacted = DiagnosticRedaction.redact(line)
            assertFalse("$line became $redacted", "abc123" in redacted)
            assertTrue(redacted, "[redacted]" in redacted)
        }
    }

    @Test
    fun `personal values are removed and stack frames survive (ADR 0002)`() {
        assertEquals("[redacted]", DiagnosticRedaction.redact("someone@example.com"))
        assertEquals("[redacted]", DiagnosticRedaction.redact("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6"))
        assertEquals("[app file]", DiagnosticRedaction.redact("/storage/emulated/0/Download/TheoriaCodex/12345.png"))
        assertEquals("[app file]:12", DiagnosticRedaction.redact("/data/data/com.theoriacodex/files/12345.png:12"))
        val frame = "at com.theoriacodex.app.viewer.ViewerVideoPlayer.onPlayerError(ViewerVideoPlayer.kt:230)"
        assertEquals(frame, DiagnosticRedaction.redact(frame))
    }

    @Test
    fun `extension source ids survive opaque-value redaction (ADR 0002)`() {
        assertEquals("custom_mihon_1234567890123456789 image failed", DiagnosticRedaction.redact("custom_mihon_1234567890123456789 image failed"))
        assertEquals("custom_aniyomi_1234567890123456789 stream failed", DiagnosticRedaction.redact("custom_aniyomi_1234567890123456789 stream failed"))
        assertEquals("[redacted] failed", DiagnosticRedaction.redact("custom_mihon_1234567890123456789-secret failed"))
    }
}
