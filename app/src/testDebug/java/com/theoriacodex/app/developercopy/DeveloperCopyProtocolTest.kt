package com.theoriacodex.app.developercopy

import com.theoriacodex.app.sourceauth.SourceCredentialSnapshot
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.ProfileBackup
import com.theoriacodex.data.repository.ProfileBackupCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperCopyProtocolTest {
    private val backup = ProfileBackupCodec().encode(ProfileBackup(0, AppSettings(),
        BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())))

    @Test fun `bounded wire preserves all accounts and profile snapshot without secret string representations`() {
        val credentials = SourceCredentialSnapshot("access-secret", "refresh-secret", 1000, "user", "api-secret", "r-user", "r-secret")
        val original = DeveloperCopyPayload(backup, credentials)
        val output = ByteArrayOutputStream()
        writeCopyPayload(output, original)
        val copy = readCopyPayload(ByteArrayInputStream(output.toByteArray()))
        assertTrue(backup.contentEquals(copy.backup))
        assertEquals(credentials, copy.credentials)
        assertFalse(copy.toString().contains("secret"))
        assertFalse(copy.credentials.toString().contains("secret"))
    }

    @Test fun `truncated oversized wrong version and trailing data are rejected before import`() {
        val output = ByteArrayOutputStream()
        writeCopyPayload(output, DeveloperCopyPayload(backup, SourceCredentialSnapshot()))
        val valid = output.toByteArray()
        val oversized = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeInt(0x54434431); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        listOf(valid.copyOf(valid.size - 1), valid + byteArrayOf(1), oversized, byteArrayOf(0, 0, 0, 0)).forEach {
            assertTrue(runCatching { readCopyPayload(ByteArrayInputStream(it)) }.isFailure)
        }
    }

    @Test fun `only the exact Debug caller with a matching certificate is accepted`() {
        requireCopyCaller(listOf(DEBUG_PACKAGE), true)
        listOf(emptyList(), listOf(PRODUCTION_PACKAGE), listOf("evil.app"), listOf(DEBUG_PACKAGE, "other.app")).forEach {
            assertTrue(runCatching { requireCopyCaller(it, true) }.isFailure)
        }
        assertTrue(runCatching { requireCopyCaller(listOf(DEBUG_PACKAGE), false) }.isFailure)
    }
}
