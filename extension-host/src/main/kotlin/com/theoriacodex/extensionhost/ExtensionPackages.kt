package com.theoriacodex.extensionhost

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.FileObserver
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** Where an extension's code is on the device, which decides how it is named and loaded. */
enum class ExtensionOrigin {
    /** An installed package, such as one Mihon or Aniyomi installed; its code is Android's own read-only copy. */
    PACKAGE,

    /** Installed by this app from a repo into its own storage, which only this app writes. */
    PRIVATE,

    /** An APK in the debug builds' test folder. */
    FILE,
}

/**
 * An extension as the package manager describes it. Reading one runs none of its code. Two values are equal only for
 * the same content at the same place: [versionCode], [size] and [modified] change when the file is replaced, so code
 * loaded for one value is never taken for another's.
 */
data class ExtensionPackage(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    /** SHA-256 of its one signing certificate, lowercase hex; null when it has none that can be read. */
    val signer: String?,
    /** Its code: the installed package's own APK, or a file in one of the app's extension folders. */
    val apk: File,
    val origin: ExtensionOrigin,
    /** The interface it is built against, which decides how its code is loaded and presented. */
    val kind: ExtensionKind,
    val sourceClasses: List<String>,
    /**
     * What the user's choice to turn it on is kept under: the package name, so an update is the same extension and
     * another extension is not, whether Android or this app installed it. A file in the debug folder is named by its
     * file name and the package inside it, apart from any installed copy: another extension put there under the same
     * file name is not the one that was turned on.
     */
    val identity: String = if (origin == ExtensionOrigin.FILE) "file:${apk.name}:$packageName" else packageName,
    /**
     * Names this copy on the Extensions page. An installed package's path, and a private copy's (named by its
     * package), already hold its package name; a debug file's id adds it, so a question asked about one extension
     * cannot be answered for another put in its place.
     */
    val id: String = if (origin == ExtensionOrigin.FILE) "${apk.path}#$packageName" else apk.path,
    /** The APK's length in bytes and when it was last written, as read when it was listed. */
    val size: Long = apk.length(),
    val modified: Long = apk.lastModified(),
    /** Why this host cannot run it, whoever signed it. */
    val unsupported: String? = null,
)

/**
 * Finds the extensions on the device that declare the feature of one of [kinds]: the ones this app installed into
 * [private] (each named `<package>.apk`), installed packages, and APK files in the debug [folder]; any other package
 * is no extension. A private copy stands in for an installed package of the same name, which is then not listed:
 * both are the same extension to the user's choices. Seeing other apps' packages needs the `QUERY_ALL_PACKAGES`
 * permission; without it they are not found.
 */
class ExtensionPackages(
    private val context: Context,
    private val kinds: List<ExtensionKind>,
    private val private: () -> File? = { null },
    private val folder: () -> File? = { null },
) {
    /** Private copies and installed packages by name, then the debug folder's files by file name. */
    fun list(): List<ExtensionPackage> {
        val owned = apkFiles(private()).mapNotNull { read(it, ExtensionOrigin.PRIVATE) }.filter { it.apk.nameWithoutExtension == it.packageName }
        val names = owned.mapTo(HashSet(), ExtensionPackage::packageName)
        val installed = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(FLAGS)).mapNotNull { info ->
            info.applicationInfo?.sourceDir?.takeIf { info.packageName !in names }?.let { apk -> extension(info, File(apk), ExtensionOrigin.PACKAGE) }
        }
        val files = apkFiles(folder()).mapNotNull(::read)
        return (owned + installed).sortedBy(ExtensionPackage::name) + files
    }

    /**
     * Calls [changed], on a thread of its own, whenever an APK file in [folder] is written, moved or deleted; null
     * without a folder. Watches only while the returned observer is held. Installed packages announce their changes
     * by broadcast instead.
     */
    fun watchFolder(changed: () -> Unit): FileObserver? {
        val folder = folder() ?: return null
        return object : FileObserver(folder, FOLDER_CHANGES) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.endsWith(".apk") == true) changed()
            }
        }.apply { startWatching() }
    }

    private fun apkFiles(folder: File?): List<File> = folder?.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy(File::getName)

    /** The extension in the file [apk], found at [origin]; null when the file is not one. */
    fun read(apk: File, origin: ExtensionOrigin = ExtensionOrigin.FILE): ExtensionPackage? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(FLAGS)) ?: return null
        // An archive's label can only be read once it is told where its resources are.
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

    /** Several signers at once would need every one of them trusted; no extension is built that way, so it is refused. */
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
