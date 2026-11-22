package com.theoriacodex.app.extensions

import android.app.Application
import android.util.Log
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.ExtensionSource
import com.theoriacodex.app.di.ExtensionSourceHost
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.mihonhost.ExtensionLoader
import com.theoriacodex.mihonhost.ExtensionImages
import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.MihonSourceAdapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import okhttp3.Interceptor

/**
 * Debug-only spike: turns Mihon extension APKs into sources. There is no install screen; push an APK with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/` and restart the app.
 * The APK's code then runs inside the app, so push only extensions you trust.
 */
internal class MihonExtensionSourceHost : ExtensionSourceHost {
    private val images = ExtensionImages()

    override val mediaInterceptor: Interceptor get() = images

    /** Never throws: the app's own sources wait for this list, so an extension that fails is logged and left out. */
    override fun load(application: Application): List<ExtensionSource> = try {
        val apks = application.getExternalFilesDir(DIRECTORY)?.listFiles { file -> file.extension == "apk" }.orEmpty()
        if (apks.isNotEmpty()) MihonHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val loader = ExtensionLoader(application)
        val loaded = HashSet<SourceKey>()
        apks.sortedBy { it.name }.mapNotNull { apk -> source(loader, apk, loaded) }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "No extensions loaded", error)
        emptyList()
    } finally {
        images.finishedLoading()
    }

    /**
     * An extension can offer one source per language; one is enough here: English, else the first. Reading a source's
     * name or address already runs its code, which can fail like any other call into it.
     */
    private fun source(loader: ExtensionLoader, apk: File, loaded: MutableSet<SourceKey>): ExtensionSource? = try {
        val extension = loader.load(apk)
        Log.i(TAG, "Loaded ${extension.name} ${extension.versionName}: ${extension.sources.size} sources, signer ${extension.signatureSha256}")
        val source = extension.sources.firstOrNull { it.lang == "en" } ?: extension.sources.first()
        val key = MihonSourceAdapter.sourceKey(source)
        val homeUrl = (source as HttpSource).baseUrl
        if (loaded.add(key)) {
            ExtensionSource(key, source.name, homeUrl, MihonSourceAdapter(key, source, images) { Log.i(TAG, it) })
        } else {
            // The same extension under a second file name. Only the first one's source may answer image requests.
            Log.w(TAG, "Skipped ${apk.name}: ${source.name} is already loaded")
            null
        }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        skipped(apk, error)
    } catch (error: LinkageError) {
        // The extension needs a part of Mihon's host API this module lacks.
        skipped(apk, error)
    }

    private fun skipped(apk: File, error: Throwable): ExtensionSource? {
        Log.w(TAG, "Skipped ${apk.name}: ${error.message}", error)
        return null
    }

    private companion object {
        const val TAG = "MihonHost"
        const val DIRECTORY = "mihon-extensions"
    }
}
