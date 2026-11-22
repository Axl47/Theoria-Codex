package com.theoriacodex.app.extensions

import android.app.Application
import android.util.Log
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.ExtensionSource
import com.theoriacodex.app.di.ExtensionSourceHost
import com.theoriacodex.mihonhost.ExtensionLoader
import com.theoriacodex.mihonhost.ExtensionImages
import com.theoriacodex.mihonhost.ExtensionRejectedException
import com.theoriacodex.mihonhost.LoadedExtension
import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.MihonSourceAdapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Interceptor

/**
 * Debug-only spike: turns Mihon extension APKs into sources. There is no install screen; push an APK with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/` and restart the app.
 * The APK's code then runs inside the app, so push only extensions you trust.
 */
internal class MihonExtensionSourceHost : ExtensionSourceHost {
    private val images = ExtensionImages()

    override val mediaInterceptor: Interceptor get() = images

    override fun load(application: Application): List<ExtensionSource> = try {
        loadAll(application)
    } finally {
        images.finishedLoading()
    }

    private fun loadAll(application: Application): List<ExtensionSource> {
        val apks = application.getExternalFilesDir(DIRECTORY)?.listFiles { file -> file.extension == "apk" }.orEmpty()
        if (apks.isEmpty()) return emptyList()
        MihonHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val loader = ExtensionLoader(application)
        return apks.sortedBy { it.name }.mapNotNull { apk ->
            try {
                loader.load(apk).also { Log.i(TAG, "Loaded ${it.name} ${it.versionName}: ${it.sources.size} sources, signer ${it.signatureSha256}") }
            } catch (error: ExtensionRejectedException) {
                Log.w(TAG, "Skipped ${apk.name}: ${error.message}", error.cause)
                null
            }
        }.mapNotNull(::source)
    }

    /** An extension can offer one source per language; one is enough here: English, else the first. */
    private fun source(extension: LoadedExtension): ExtensionSource? {
        val source = extension.sources.firstOrNull { it.lang == "en" } ?: extension.sources.firstOrNull() ?: return null
        val homeUrl = (source as? HttpSource)?.baseUrl ?: return null
        val key = MihonSourceAdapter.sourceKey(source)
        return ExtensionSource(key, source.name, homeUrl, MihonSourceAdapter(key, source, images) { Log.i(TAG, it) })
    }

    private companion object {
        const val TAG = "MihonHost"
        const val DIRECTORY = "mihon-extensions"
    }
}
