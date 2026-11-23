package com.theoriacodex.extensionhost

import android.content.Context
import dalvik.system.DelegateLastClassLoader
import java.io.File

/** Why an extension was not loaded; [message] is for logs and the Extensions page. */
class ExtensionRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads an extension's code into this process. Its manifest names the classes to create, its [ExtensionKind] turns
 * them into sources, and its code links against the host API in this module (`eu.kanade.tachiyomi`,
 * `androidx.preference`) and against the libraries the app already ships.
 *
 * Loaded code runs with the app's own permissions and can read the app's data, so the caller loads only extensions the
 * user turned on (see [ExtensionRegistry]). [ExtensionHost.install] must have run first.
 */
class ExtensionLoader(private val context: Context, private val packages: ExtensionPackages) {
    /**
     * Copies an earlier process left behind are removed once, before this process makes its first. The folder kept
     * the name it had when Mihon's were the only extensions, so no copy an earlier build made is left behind.
     */
    private val copies by lazy {
        File(context.codeCacheDir, "mihon-extensions").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** [host] is what each of its sources is given. @throws ExtensionRejectedException when [extension] is not loadable by this host. */
    fun load(extension: ExtensionPackage, host: HostServices): List<HostedSource> {
        extension.unsupported?.let { throw ExtensionRejectedException("${extension.packageName}: $it") }
        // An installed package's APK is Android's own read-only copy, so its code loads from where it is.
        val code = if (extension.installed) extension.apk else privateReadOnlyCopy(extension)
        val classLoader = DelegateLastClassLoader(code.path, context.classLoader)
        return extension.sourceClasses.flatMap { className -> createSources(classLoader, className, extension, host) }
    }

    /**
     * Android refuses to load code from a file the app could still write, so a file from the folder is copied and
     * locked first. Every load gets a copy of its own, never written again, so code already loaded from an earlier
     * copy is not changed under it. The copy is what runs, so it is the copy that must be what the user turned on:
     * the same package, signer, version and length as the file that was listed.
     */
    private fun privateReadOnlyCopy(extension: ExtensionPackage): File {
        val target = File.createTempFile(extension.apk.nameWithoutExtension, ".apk", copies)
        extension.apk.copyTo(target, overwrite = true)
        check(target.setReadOnly()) { "Could not lock ${target.name}" }
        val copy = packages.read(target)
        val same = copy != null && copy.packageName == extension.packageName && copy.signer == extension.signer &&
            copy.versionCode == extension.versionCode && copy.size == extension.size
        if (extension.signer == null || !same) {
            target.delete()
            throw ExtensionRejectedException("${extension.apk.name} changed after it was listed")
        }
        return target
    }

    /**
     * A missing host class or method surfaces here or on a later call as a [LinkageError], which is the signal that
     * the extension needs more of the host API than this module provides.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createSources(classLoader: ClassLoader, className: String, extension: ExtensionPackage, host: HostServices): List<HostedSource> = try {
        val instance = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()
        extension.kind.adapt(instance, host)
            ?: throw ExtensionRejectedException("$className in ${extension.packageName} is neither a Source nor a SourceFactory")
    } catch (error: ExtensionRejectedException) {
        throw error
    } catch (error: Throwable) {
        throw ExtensionRejectedException("$className in ${extension.packageName} could not be created: $error", error)
    }
}
