package com.theoriacodex.app.extensions

import android.app.Application
import android.util.Log
import com.theoriacodex.app.BuildConfig
import com.theoriacodex.app.di.ExtensionSourceHost
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.mihonhost.ExtensionImages
import com.theoriacodex.mihonhost.ExtensionLoader
import com.theoriacodex.mihonhost.ExtensionPackages
import com.theoriacodex.mihonhost.ExtensionRegistry
import com.theoriacodex.mihonhost.MihonHost
import com.theoriacodex.mihonhost.SourceSettings
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor

/**
 * Debug builds only: turns Mihon extensions into series sources. It finds the extensions Mihon installed as packages,
 * and APK files pushed for tests with
 * `adb push <apk> /sdcard/Android/data/com.theoriacodex.debug/files/mihon-extensions/`.
 * None of them runs until the user turns it on in the Extensions page; [ExtensionRegistry] holds that rule.
 */
internal class MihonExtensionSourceHost : ExtensionSourceHost {
    private lateinit var registry: ExtensionRegistry

    /** The settings store, which opens after the HTTP clients that already hold [mediaInterceptor]. */
    private val choices = CompletableDeferred<ExtensionChoiceStore>()

    // Runs on an HTTP client's thread, for an image of a source that is not on: loads the extensions if nothing has yet.
    private val images = ExtensionImages { runBlocking { registry.sources() } }

    override val mediaInterceptor: Interceptor get() = images
    override val extensions: SeriesExtensions get() = registry

    override fun attach(application: Application) {
        val packages = ExtensionPackages(application) { application.getExternalFilesDir(DIRECTORY) }
        val loader = ExtensionLoader(application, packages)
        // What extensions ask their host for is set up when the first one loads, not at startup.
        val hostInstalled = lazy { MihonHost.install(application, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) }
        registry = ExtensionRegistry(
            packages = packages::list,
            load = { extension ->
                hostInstalled.value
                loader.load(extension)
            },
            choices = object : ExtensionChoiceStore {
                override suspend fun choices(): ExtensionChoices = choices.await().choices()

                override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) = choices.await().update(transform)
            },
            images = images,
            settings = { source -> SourceSettings(application, source, source.sourcePreferences()) },
            deviceLanguages = { application.resources.configuration.locales.toLanguageTags().split(',') },
            log = { Log.i(TAG, it) },
        )
    }

    override fun bind(choices: ExtensionChoiceStore) {
        this.choices.complete(choices)
    }

    override suspend fun sources(): List<SeriesSource> = registry.sources()

    private companion object {
        const val TAG = "MihonHost"
        const val DIRECTORY = "mihon-extensions"
    }
}
