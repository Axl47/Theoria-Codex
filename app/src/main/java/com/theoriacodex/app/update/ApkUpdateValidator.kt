package com.theoriacodex.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
        val archiveInfo = packageManager.readArchivePackageInfo(apkFile)
            ?: error("Downloaded update APK is unreadable")
        val archiveVersion = archiveInfo.longVersionCode
        val installedInfo = packageManager.readInstalledPackageInfo(context.packageName)
        val installedVersion = installedInfo.longVersionCode
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

    private fun PackageManager.readArchivePackageInfo(apkFile: File): PackageInfo? {
        val info = getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )

        info?.applicationInfo?.sourceDir = apkFile.absolutePath
        info?.applicationInfo?.publicSourceDir = apkFile.absolutePath
        return info
    }

    private fun PackageManager.readInstalledPackageInfo(packageName: String): PackageInfo {
        return getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    }

    private fun PackageInfo.signatureDigests(): Set<String> {
        val signingInfo = signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val signatureBytes = signatures.orEmpty().map { it.toByteArray() }

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
