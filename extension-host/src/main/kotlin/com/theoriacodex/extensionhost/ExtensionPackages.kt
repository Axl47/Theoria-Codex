package com.theoriacodex.extensionhost

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.FileObserver
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

enum class ExtensionOrigin {
    PACKAGE,

    PRIVATE,

    FILE,
}

data class ExtensionPackage(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val signer: String?,
    val apk: File,
    val origin: ExtensionOrigin,
    val kind: ExtensionKind,
    val sourceClasses: List<String>,
    val identity: String = if (origin == ExtensionOrigin.FILE) "file:${apk.name}:$packageName" else packageName,
    val id: String = if (origin == ExtensionOrigin.FILE) "${apk.path}#$packageName" else apk.path,
    val size: Long = apk.length(),
    val modified: Long = apk.lastModified(),
    val unsupported: String? = null,
)

class ExtensionPackages(
    private val context: Context,
    private val kinds: List<ExtensionKind>,
    private val private: () -> File? = { null },
    private val folder: () -> File? = { null },
) {
    private val archives = ConcurrentHashMap<File, Archive>()

    private class Archive(val size: Long, val modified: Long, val origin: ExtensionOrigin, val extension: ExtensionPackage?)

    fun list(): List<ExtensionPackage> {
        val owned = apkFiles(private()).mapNotNull { listed(it, ExtensionOrigin.PRIVATE) }.filter { it.apk.nameWithoutExtension == it.packageName }
        val installed = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(FLAGS)).mapNotNull { info ->
            info.applicationInfo?.sourceDir?.let { apk -> extension(info, File(apk), ExtensionOrigin.PACKAGE) }
        }
        val files = apkFiles(folder()).mapNotNull { listed(it, ExtensionOrigin.FILE) }
        return standing(owned, installed).sortedBy(ExtensionPackage::name) + files
    }

    private fun listed(apk: File, origin: ExtensionOrigin): ExtensionPackage? {
        val size = apk.length()
        val modified = apk.lastModified()
        archives[apk]?.takeIf { it.size == size && it.modified == modified && it.origin == origin }?.let { return it.extension }
        return read(apk, origin).also { archives[apk] = Archive(size, modified, origin, it) }
    }

    fun watchFolder(changed: () -> Unit): FileObserver? {
        val folder = folder() ?: return null
        return object : FileObserver(folder, FOLDER_CHANGES) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.endsWith(".apk") == true) changed()
            }
        }.apply { startWatching() }
    }

    private fun apkFiles(folder: File?): List<File> = folder?.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy(File::getName)

    fun read(apk: File, origin: ExtensionOrigin = ExtensionOrigin.FILE): ExtensionPackage? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(FLAGS)) ?: return null
        info.applicationInfo?.apply {
            sourceDir = apk.path
            publicSourceDir = apk.path
        }
        return extension(info, apk, origin)
    }

    private fun extension(info: PackageInfo, apk: File, origin: ExtensionOrigin): ExtensionPackage? {
        val kind = kindOf(info) ?: return null
        val classes = info.applicationInfo?.metaData?.getString(kind.sourceClassesKey).orEmpty().split(';').map(String::trim).filter(String::isNotEmpty)
            .map { name -> if (name.startsWith('.')) info.packageName + name else name }
        return ExtensionPackage(
            packageName = info.packageName,
            name = info.applicationInfo?.loadLabel(context.packageManager)?.toString()?.removePrefix(kind.labelPrefix)?.ifBlank { null } ?: info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            signer = signer(info),
            apk = apk,
            origin = origin,
            kind = kind,
            sourceClasses = classes,
            unsupported = kind.unsupported(info) ?: "Names no source".takeIf { classes.isEmpty() },
        )
    }

    private fun kindOf(info: PackageInfo): ExtensionKind? {
        val features = info.reqFeatures.orEmpty().mapTo(HashSet()) { it.name }
        return kinds.firstOrNull { it.feature in features }
    }

    private fun signer(info: PackageInfo): String? {
        val signing = info.signingInfo?.takeUnless { it.hasMultipleSigners() } ?: return null
        val certificate = signing.apkContentsSigners?.singleOrNull() ?: return null
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()))
    }

    private companion object {
        const val FLAGS = (PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
        const val FOLDER_CHANGES = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE
    }
}

internal fun standing(owned: List<ExtensionPackage>, installed: List<ExtensionPackage>): List<ExtensionPackage> {
    val packages = installed.associateBy(ExtensionPackage::packageName)
    val copies = owned.filterNot { copy -> packages[copy.packageName]?.let { it.signer == copy.signer && it.versionCode > copy.versionCode } == true }
    val names = copies.mapTo(HashSet(), ExtensionPackage::packageName)
    return copies + installed.filter { it.packageName !in names }
}
