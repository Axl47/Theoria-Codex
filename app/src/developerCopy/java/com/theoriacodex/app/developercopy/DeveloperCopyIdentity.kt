package com.theoriacodex.app.developercopy

import android.content.pm.PackageManager

/** Pin the exact current signing certificate, rather than trusting a package name or shared debug key name. */
internal fun PackageManager.requireCopySigner(packageName: String, expected: String) {
    require(expected.matches(Regex("[0-9a-f]{64}")))
    val certificates = getPackageInfo(
        packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    ).signingInfo?.apkContentsSigners.orEmpty()
    require(certificates.size == 1 && sha256(certificates.single().toByteArray()) == expected) {
        "Copy signing identity does not match"
    }
}

internal fun requireCopyCaller(packages: List<String>, certificatesMatch: Boolean) {
    require(packages == listOf(DEBUG_PACKAGE) && certificatesMatch) { "Copy caller is not authorized" }
}
