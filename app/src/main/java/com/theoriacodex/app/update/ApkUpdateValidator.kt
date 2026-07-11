package com.theoriacodex.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

class ApkUpdateValidator(
    private val context: Context,
) {
    fun validate(
        apkFile: File,
        expectedVersionCode: Int,
    ): Result<Unit> = runCatching {
        val packageManager = context.packageManager
        val archiveInfo = packageManager.getArchivePackageInfoCompat(apkFile)
            ?: error("Downloaded update APK is unreadable")
        val archiveVersion = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedInfo = packageManager.getInstalledPackageInfoCompat(context.packageName)
        val installedVersion = PackageInfoCompat.getLongVersionCode(installedInfo)
        val archiveSignatures = archiveInfo.signatureDigests()
        val installedSignatures = installedInfo.signatureDigests()
        validateApkIdentityPolicy(
            installed = ApkIdentity(
                packageName = context.packageName,
                versionCode = installedVersion,
                signatureDigests = installedSignatures,
            ),
            archive = ApkIdentity(
                packageName = archiveInfo.packageName,
                versionCode = archiveVersion,
                signatureDigests = archiveSignatures,
            ),
            expectedVersionCode = expectedVersionCode.toLong(),
        ).getOrThrow()
    }

    private fun PackageManager.getArchivePackageInfoCompat(apkFile: File): PackageInfo? {
        val legacyFlags = legacySignatureFlags()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(legacyFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(apkFile.absolutePath, legacyFlags)
        }

        info?.applicationInfo?.sourceDir = apkFile.absolutePath
        info?.applicationInfo?.publicSourceDir = apkFile.absolutePath
        return info
    }

    private fun PackageManager.getInstalledPackageInfoCompat(packageName: String): PackageInfo {
        val legacyFlags = legacySignatureFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(legacyFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, legacyFlags)
        }
    }

    private fun legacySignatureFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun PackageInfo.signatureDigests(): Set<String> {
        val signatureBytes: List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = signingInfo ?: return emptySet()
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty().map { it.toByteArray() }
        }

        if (signatureBytes.isEmpty()) return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatureBytes.map { bytes ->
            digest.digest(bytes).joinToString(separator = "") { b -> "%02x".format(b) }
        }.toSet()
    }
}

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signatureDigests: Set<String>,
)

/** Pure release policy; Android package parsing remains a thin boundary above it. */
internal fun validateApkIdentityPolicy(
    installed: ApkIdentity,
    archive: ApkIdentity,
    expectedVersionCode: Long,
): Result<Unit> = runCatching {
    if (archive.packageName != installed.packageName) {
        error("Downloaded APK package does not match installed app")
    }
    if (archive.versionCode != expectedVersionCode) {
        error("Downloaded APK version does not match release metadata")
    }
    if (archive.versionCode <= installed.versionCode) {
        error("Downloaded APK is not newer than installed version")
    }
    if (archive.signatureDigests.isEmpty() || installed.signatureDigests.isEmpty()) {
        error("Could not verify APK signatures")
    }
    if (archive.signatureDigests.intersect(installed.signatureDigests).isEmpty()) {
        error("Downloaded APK signature does not match installed app (release key mismatch)")
    }
}
