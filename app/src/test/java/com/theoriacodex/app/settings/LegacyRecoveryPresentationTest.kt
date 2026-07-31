package com.theoriacodex.app.settings

import com.theoriacodex.data.storage.CorruptionRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyRecoveryPresentationTest {
    @Test
    fun `summary keeps diagnostic identity out of the Settings card`() {
        val fullPath = "/private/app/query_store.json.corrupt-7-${"a".repeat(64)}"
        val recovery = CorruptionRecovery(
            reason = "query_store.json contains malformed JSON",
            backupPath = fullPath,
            logicalStore = "Saved searches",
            logicalFile = "query_store.json",
            sha256 = "a".repeat(64),
            byteCount = 7L,
        )

        val summary = legacyRecoverySummary(recovery)

        assertEquals(
            "Saved searches was reset after unreadable local data was preserved (7 bytes, checksum aaaaaaaa).",
            summary,
        )
        assertFalse(summary.contains(fullPath))
        assertFalse(summary.contains(recovery.reason))
        assertFalse(summary.contains(recovery.sha256))
    }
}
