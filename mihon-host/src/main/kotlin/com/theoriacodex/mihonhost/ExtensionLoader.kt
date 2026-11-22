package com.theoriacodex.mihonhost

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.DelegateLastClassLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** An extension whose code is loaded and whose sources are ready to call. */
class LoadedExtension(
    val packageName: String,
    val name: String,
    val versionName: String,
    /** SHA-256 of the signing certificate, lowercase hex; who built the APK. */
    val signatureSha256: String,
    val sources: List<Source>,
)

/** Why an APK was not loaded; [message] is for logs. */
class ExtensionRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads a Mihon extension APK into this process. An extension is an ordinary APK that is never installed: its
 * manifest names the classes to create, and its code links against the host API in this module (`eu.kanade.tachiyomi`)
 * and against the libraries the app already ships.
 *
 * Loaded code runs with the app's own permissions and can read the app's data, so only load APKs you trust.
 * [MihonHost.install] must have run first.
 */
class ExtensionLoader(private val context: Context) {
    /** @throws ExtensionRejectedException when [apk] is not a loadable extension for this host. */
    fun load(apk: File): LoadedExtension {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(ARCHIVE_FLAGS))
            ?: throw ExtensionRejectedException("${apk.name} is not a readable APK")
        if (info.reqFeatures.orEmpty().none { it.name == EXTENSION_FEATURE }) {
            throw ExtensionRejectedException("${info.packageName} does not declare the $EXTENSION_FEATURE feature")
        }
        val metaData = info.applicationInfo?.metaData ?: throw ExtensionRejectedException("${info.packageName} has no metadata")
        val libVersion = metaData.getFloat(METADATA_LIB_VERSION)
        if (libVersion != SUPPORTED_LIB_VERSION) {
            throw ExtensionRejectedException("${info.packageName} targets extension-lib $libVersion; this host implements $SUPPORTED_LIB_VERSION")
        }
        val classNames = metaData.getString(METADATA_SOURCE_CLASS).orEmpty().split(';').map(String::trim).filter(String::isNotEmpty)
            .map { name -> if (name.startsWith('.')) info.packageName + name else name }
        if (classNames.isEmpty()) throw ExtensionRejectedException("${info.packageName} names no source class")

        val classLoader = DelegateLastClassLoader(privateReadOnlyCopy(apk).path, context.classLoader)
        val sources = classNames.flatMap { className -> createSources(classLoader, className, info.packageName) }
        return LoadedExtension(
            packageName = info.packageName,
            name = metaData.getString(METADATA_NAME) ?: info.packageName,
            versionName = info.versionName.orEmpty(),
            signatureSha256 = signatureSha256(info),
            sources = sources,
        )
    }

    /** Android refuses to load code from a file the app could still write, so the APK is copied and locked first. */
    private fun privateReadOnlyCopy(apk: File): File {
        val directory = File(context.codeCacheDir, "mihon-extensions").apply { mkdirs() }
        // One copy per file: a second file of the same extension must not replace code that is already loaded.
        val target = File(directory, apk.name)
        target.delete()
        apk.copyTo(target)
        check(target.setReadOnly()) { "Could not lock ${target.name}" }
        return target
    }

    /**
     * A missing host class or method surfaces here or on a later call as a [LinkageError], which is the signal that
     * the extension needs more of Mihon's host API than this module provides.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createSources(classLoader: ClassLoader, className: String, packageName: String): List<Source> = try {
        when (val instance = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()) {
            is Source -> listOf(instance)
            is SourceFactory -> instance.createSources()
            else -> throw ExtensionRejectedException("$className in $packageName is neither a Source nor a SourceFactory")
        }
    } catch (error: ExtensionRejectedException) {
        throw error
    } catch (error: Throwable) {
        throw ExtensionRejectedException("$className in $packageName could not be created: $error", error)
    }

    private fun signatureSha256(info: PackageInfo): String {
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
    }

    private companion object {
        const val ARCHIVE_FLAGS =
            (PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
        const val EXTENSION_FEATURE = "tachiyomi.extension"
        const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
        const val METADATA_NAME = "tachiyomix.name"
        const val METADATA_LIB_VERSION = "tachiyomix.extensionLib"
        const val SUPPORTED_LIB_VERSION = 1.6f
    }
}
