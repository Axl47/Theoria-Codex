package com.theoriacodex.mihonhost

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** An extension as the package manager describes it. Reading one runs none of its code. */
data class ExtensionPackage(
    val packageName: String,
    val name: String,
    val versionName: String,
    /** SHA-256 of its one signing certificate, lowercase hex; null when it has none that can be read. */
    val signer: String?,
    /** Its code: the installed package's own APK, or a file in the app's extensions folder. */
    val apk: File,
    val installed: Boolean,
    val sourceClasses: List<String>,
    /** Why this host cannot run it, whoever signed it. */
    val unsupported: String? = null,
)

/**
 * Finds the extensions on the device: packages Mihon installed, which declare the `tachiyomi.extension` feature, and
 * APK files in [folder]. Seeing other apps' packages needs the `QUERY_ALL_PACKAGES` permission; without it only the
 * folder is found.
 */
class ExtensionPackages(private val context: Context, private val folder: () -> File? = { null }) {
    /** Installed packages first, then the folder's files by name. */
    fun list(): List<ExtensionPackage> {
        val installed = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(FLAGS)).mapNotNull { info ->
            info.applicationInfo?.sourceDir?.let { apk -> extension(info, File(apk), installed = true) }
        }
        val files = folder()?.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy(File::getName).mapNotNull(::read)
        return installed.sortedBy(ExtensionPackage::name) + files
    }

    /** The extension in the file [apk]; null when the file is not one. */
    fun read(apk: File): ExtensionPackage? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(FLAGS)) ?: return null
        // An archive's label can only be read once it is told where its resources are.
        info.applicationInfo?.apply {
            sourceDir = apk.path
            publicSourceDir = apk.path
        }
        return extension(info, apk, installed = false)
    }

    private fun extension(info: PackageInfo, apk: File, installed: Boolean): ExtensionPackage? {
        if (info.reqFeatures.orEmpty().none { it.name == EXTENSION_FEATURE }) return null
        val metaData = info.applicationInfo?.metaData
        val libVersion = metaData?.getFloat(METADATA_LIB_VERSION)
        val classes = metaData?.getString(METADATA_SOURCE_CLASS).orEmpty().split(';').map(String::trim).filter(String::isNotEmpty)
            .map { name -> if (name.startsWith('.')) info.packageName + name else name }
        return ExtensionPackage(
            packageName = info.packageName,
            name = info.applicationInfo?.loadLabel(context.packageManager)?.toString()?.removePrefix(LABEL_PREFIX)?.ifBlank { null } ?: info.packageName,
            versionName = info.versionName.orEmpty(),
            signer = signer(info),
            apk = apk,
            installed = installed,
            sourceClasses = classes,
            unsupported = when {
                libVersion != SUPPORTED_LIB_VERSION -> "Needs extension API $libVersion"
                classes.isEmpty() -> "Names no source"
                else -> null
            },
        )
    }

    /** Several signers at once would need every one of them trusted; no extension is built that way, so it is refused. */
    private fun signer(info: PackageInfo): String? {
        val signing = info.signingInfo?.takeUnless { it.hasMultipleSigners() } ?: return null
        val certificate = signing.apkContentsSigners?.singleOrNull() ?: return null
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()))
    }

    private companion object {
        const val FLAGS = (PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        const val METADATA_LIB_VERSION = "tachiyomix.extensionLib"
        const val SUPPORTED_LIB_VERSION = 1.6f

        /** Extensions label themselves "Tachiyomi: <name>". */
        const val LABEL_PREFIX = "Tachiyomi: "
    }
}
