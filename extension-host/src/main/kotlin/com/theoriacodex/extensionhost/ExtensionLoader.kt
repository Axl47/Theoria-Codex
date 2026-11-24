package com.theoriacodex.extensionhost

import android.app.Application
import android.content.Context
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import dalvik.system.DelegateLastClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException

class ExtensionRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ExtensionLoader private constructor(private val context: Context, private val read: (File) -> ExtensionPackage?) {
    private val copies by lazy {
        File(context.codeCacheDir, "mihon-extensions").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    fun load(extension: ExtensionPackage, host: HostServices): List<HostedSource> {
        extension.unsupported?.let { throw ExtensionRejectedException("${extension.packageName}: $it") }
        val code = if (extension.origin == ExtensionOrigin.PACKAGE) extension.apk else privateReadOnlyCopy(extension)
        return sources(DelegateLastClassLoader(code.path, context.classLoader), extension, host)
    }

    internal fun sources(classLoader: ClassLoader, extension: ExtensionPackage, host: HostServices): List<HostedSource> =
        extension.sourceClasses.flatMap { className -> createSources(classLoader, className, extension, host) }

    private fun privateReadOnlyCopy(extension: ExtensionPackage): File {
        val target = File.createTempFile(extension.apk.nameWithoutExtension, ".apk", copies)
        extension.apk.copyTo(target, overwrite = true)
        check(target.setReadOnly()) { "Could not lock ${target.name}" }
        val copy = read(target)
        val same = copy != null && copy.packageName == extension.packageName && copy.signer == extension.signer &&
            copy.versionCode == extension.versionCode && copy.size == extension.size
        if (extension.signer == null || !same) {
            target.delete()
            throw ExtensionRejectedException("${extension.apk.name} changed after it was listed")
        }
        return target
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createSources(classLoader: ClassLoader, className: String, extension: ExtensionPackage, host: HostServices): List<HostedSource> = try {
        val instance = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()
        extension.kind.adapt(instance, host)
            ?: throw ExtensionRejectedException("$className in ${extension.packageName} is not a source of its kind")
    } catch (error: ExtensionRejectedException) {
        throw error
    } catch (error: Throwable) {
        generateSequence(error, ::wrapped).take(MAX_WRAPPING).firstOrNull { it.endsTheProcess() }?.let { throw it }
        throw ExtensionRejectedException("$className in ${extension.packageName} could not be created: $error", error)
    }

    private fun wrapped(error: Throwable): Throwable? = when (error) {
        is InvocationTargetException -> error.targetException
        is ExceptionInInitializerError -> error.cause
        else -> null
    }

    companion object {
        private const val MAX_WRAPPING = 8

        fun installingHost(
            application: Application,
            versionName: String,
            versionCode: Int,
            cookies: BrowserCookieStore,
            browsers: ChallengeBrowsers,
            outages: HostOutageTracker,
            read: (File) -> ExtensionPackage?,
        ): ExtensionLoader {
            ExtensionHost.install(application, versionName, versionCode, cookies, browsers, outages)
            return ExtensionLoader(application, read)
        }
    }
}
