package com.theoriacodex.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdatePolicyTest {
    @Test
    fun `accepts a newer expected build with current or historical signing overlap`() {
        assertTrue(
            validateApkIdentityPolicy(
                installed = identity(version = 10L, signatures = setOf("old", "current")),
                archive = identity(version = 11L, signatures = setOf("current", "next")),
                expectedVersionCode = 11L,
            ).isSuccess,
        )
    }

    @Test
    fun `rejects package and release metadata mismatches`() {
        assertFailureContains(
            archive = identity(packageName = "other.package", version = 11L),
            expectedVersion = 11L,
            message = "package does not match",
        )
        assertFailureContains(
            archive = identity(version = 12L),
            expectedVersion = 11L,
            message = "version does not match release metadata",
        )
    }

    @Test
    fun `rejects equal and downgraded versions`() {
        assertFailureContains(
            archive = identity(version = 10L),
            expectedVersion = 10L,
            message = "not newer",
        )
        assertFailureContains(
            archive = identity(version = 9L),
            expectedVersion = 9L,
            message = "not newer",
        )
    }

    @Test
    fun `rejects missing or unrelated signing histories`() {
        assertFailureContains(
            archive = identity(version = 11L, signatures = emptySet()),
            expectedVersion = 11L,
            message = "Could not verify APK signatures",
        )
        val installedWithoutSignatures = validateApkIdentityPolicy(
            installed = identity(version = 10L, signatures = emptySet()),
            archive = identity(version = 11L),
            expectedVersionCode = 11L,
        )
        assertEquals(
            "Could not verify APK signatures",
            installedWithoutSignatures.exceptionOrNull()?.message,
        )
        assertFailureContains(
            archive = identity(version = 11L, signatures = setOf("different")),
            expectedVersion = 11L,
            message = "release key mismatch",
        )
    }

    private fun assertFailureContains(
        archive: ApkIdentity,
        expectedVersion: Long,
        message: String,
    ) {
        val result = validateApkIdentityPolicy(
            installed = identity(version = 10L),
            archive = archive,
            expectedVersionCode = expectedVersion,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(message))
    }

    private fun identity(
        packageName: String = "com.theoriacodex",
        version: Long,
        signatures: Set<String> = setOf("current"),
    ): ApkIdentity = ApkIdentity(
        packageName = packageName,
        versionCode = version,
        signatureDigests = signatures,
    )
}
