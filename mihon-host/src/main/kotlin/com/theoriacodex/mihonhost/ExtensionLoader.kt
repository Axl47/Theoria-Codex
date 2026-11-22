package com.theoriacodex.mihonhost

import android.content.Context
import dalvik.system.DelegateLastClassLoader
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File

/** Why an extension was not loaded; [message] is for logs and the Extensions page. */
class ExtensionRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads a Mihon extension's code into this process. Its manifest names the classes to create, and its code links
 * against the host API in this module (`eu.kanade.tachiyomi`, `androidx.preference`) and against the libraries the app
 * already ships.
 *
 * Loaded code runs with the app's own permissions and can read the app's data, so the caller loads only extensions the
 * user turned on (see [ExtensionRegistry]). [MihonHost.install] must have run first.
 */
class ExtensionLoader(private val context: Context, private val packages: ExtensionPackages) {
    /** Copies an earlier process left behind are removed once, before this process makes its first. */
    private val copies by lazy {
        File(context.codeCacheDir, "mihon-extensions").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** @throws ExtensionRejectedException when [extension] is not loadable by this host. */
    fun load(extension: ExtensionPackage): List<Source> {
        extension.unsupported?.let { throw ExtensionRejectedException("${extension.packageName}: $it") }
        // An installed package's APK is Android's own read-only copy, so its code loads from where it is.
        val code = if (extension.installed) extension.apk else privateReadOnlyCopy(extension)
        val classLoader = DelegateLastClassLoader(code.path, context.classLoader)
        return extension.sourceClasses.flatMap { className -> createSources(classLoader, className, extension.packageName) }
    }

    /**
     * Android refuses to load code from a file the app could still write, so a file from the folder is copied and
     * locked first. Every load gets a copy of its own, never written again, so code already loaded from an earlier
     * copy is not changed under it. The copy is what runs, so it is the copy that must be what the user turned on:
     * the same signer, version and length as the file that was listed.
     */
    private fun privateReadOnlyCopy(extension: ExtensionPackage): File {
        val target = File.createTempFile(extension.apk.nameWithoutExtension, ".apk", copies)
        extension.apk.copyTo(target, overwrite = true)
        check(target.setReadOnly()) { "Could not lock ${target.name}" }
        val copy = packages.read(target)
        if (extension.signer == null || copy?.signer != extension.signer || copy.versionCode != extension.versionCode || copy.size != extension.size) {
            target.delete()
            throw ExtensionRejectedException("${extension.apk.name} changed after it was listed")
        }
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
}
