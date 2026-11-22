package com.theoriacodex.app.extensions

import android.app.Application
import android.util.Log
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.ExtensionSourceHost
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.mihonhost.ExtensionImages
import com.theoriacodex.mihonhost.ExtensionLoader
import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.MihonSeriesSource
import java.io.File
import okhttp3.Interceptor

/**
 * Debug builds only: turns Mihon extension APKs into series sources. There is no install screen; push an APK with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/` and restart the app.
 * The APK's code then runs inside the app, so push only extensions you trust.
 */
internal class MihonExtensionSourceHost : ExtensionSourceHost {
    private lateinit var application: Application

    /** Loaded once, by whichever comes first: the Series tab or an extension image request. Later callers wait for it. */
    private val loaded: List<SeriesSource> by lazy(::load)
    private val images = ExtensionImages { loaded }

    override val mediaInterceptor: Interceptor get() = images

    override fun attach(application: Application) {
        this.application = application
    }

    override fun sources(): List<SeriesSource> = loaded

    /** Never throws: an extension that fails is logged and left out. */
    private fun load(): List<SeriesSource> = try {
        val apks = application.getExternalFilesDir(DIRECTORY)?.listFiles { file -> file.extension == "apk" }.orEmpty()
        if (apks.isNotEmpty()) MihonHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val loader = ExtensionLoader(application)
        val keys = HashSet<SourceKey>()
        apks.sortedBy { it.name }.mapNotNull { apk -> source(loader, apk, keys) }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "No extensions loaded", error)
        emptyList()
    }

    /**
     * An extension can offer one source per language; one is enough here: English, else the first. Reading a source's
     * name or id already runs its code, which can fail like any other call into it.
     */
    private fun source(loader: ExtensionLoader, apk: File, keys: MutableSet<SourceKey>): SeriesSource? = try {
        val extension = loader.load(apk)
        Log.i(TAG, "Loaded ${extension.name} ${extension.versionName}: ${extension.sources.size} sources, signer ${extension.signatureSha256}")
        val source = extension.sources.firstOrNull { it.lang == "en" } ?: extension.sources.first()
        val name = source.name
        if (keys.add(MihonSeriesSource.sourceKey(source))) {
            MihonSeriesSource(source, images) { Log.i(TAG, it) }
        } else {
            // The same extension under a second file name. Only the first one's source may answer image requests.
            Log.w(TAG, "Skipped ${apk.name}: $name is already loaded")
            null
        }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        skipped(apk, error)
    } catch (error: LinkageError) {
        // The extension needs a part of Mihon's host API this module lacks.
        skipped(apk, error)
    }

    private fun skipped(apk: File, error: Throwable): SeriesSource? {
        Log.w(TAG, "Skipped ${apk.name}: ${error.message}", error)
        return null
    }

    private companion object {
        const val TAG = "MihonHost"
        const val DIRECTORY = "mihon-extensions"
    }
}
