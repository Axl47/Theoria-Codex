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
        if (archiveInfo.packageName != context.packageName) {
            error("Downloaded APK package does not match installed app")
        }

        val archiveVersion = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (archiveVersion.toInt() != expectedVersionCode) {
            error("Downloaded APK version does not match release metadata")
        }

        val installedInfo = packageManager.getInstalledPackageInfoCompat(context.packageName)
        val installedVersion = PackageInfoCompat.getLongVersionCode(installedInfo)
        if (archiveVersion <= installedVersion) {
            error("Downloaded APK is not newer than installed version")
        }

        val archiveSignatures = archiveInfo.signatureDigests()
        val installedSignatures = installedInfo.signatureDigests()
        if (archiveSignatures.isEmpty() || installedSignatures.isEmpty()) {
            error("Could not verify APK signatures")
        }
        if (archiveSignatures != installedSignatures) {
            error("Downloaded APK signature does not match installed app")
        }
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
