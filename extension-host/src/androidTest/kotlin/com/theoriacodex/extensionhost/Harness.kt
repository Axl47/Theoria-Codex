package com.theoriacodex.extensionhost

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowser
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import java.io.File
import kotlin.time.TimeSource
import okhttp3.Request

/**
 * What the real-extension harnesses share: the host installed in this test package without the app's browser, an
 * extension APK loaded from the package's external files directory, and reports as text only, never provider media.
 */
internal class Harness(private val tag: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The APK named by the instrumentation argument [name], or null when the run names none. */
    fun apkNamed(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    /** Installs the host and loads [apkName] as the app would, with both kinds of extension known. */
    fun load(apkName: String, services: HostServices): Pair<ExtensionPackage, List<HostedSource>> {
        ExtensionHost.install(context.applicationContext as Application, versionName = "harness", versionCode = 1, cookies = NoCookies, browsers = ChallengeBrowsers(NoCookies, NoBrowser), outages = HostOutageTracker())
        val packages = ExtensionPackages(context, listOf(MihonKind, AnimeKind))
        val extension = checkNotNull(packages.read(File(context.getExternalFilesDir(null), apkName))) { "$apkName is not an extension" }
        val sources = timed("load") { ExtensionLoader(context, packages).load(extension, services) }
        report("extension", "${extension.name} ${extension.versionName}, ${extension.kind.seriesKind}, ${sources.size} sources, signer ${extension.signer}")
        return extension to sources
    }

    inline fun <T> timed(step: String, block: () -> T): T {
        val requests = ExtensionHost.requestCount.get()
        val started = TimeSource.Monotonic.markNow()
        return block().also {
            report("timing", "$step: ${started.elapsedNow().inWholeMilliseconds} ms, ${ExtensionHost.requestCount.get() - requests} requests")
        }
    }

    /** Shown by `am instrument -r` as it happens, and in logcat under the harness's tag. */
    fun report(key: String, message: String) {
        Log.i(tag, "$key: $message")
        instrumentation.sendStatus(0, Bundle().apply { putString("harness", "$key: $message") })
    }

    /**
     * The harness measures an extension alone, without the app's browser cookies (the WebView store lives in the app's
     * modules), so a site behind a browser check fails here as it would before any check is passed.
     */
    private object NoCookies : BrowserCookieStore {
        override fun cookieHeader(url: String): String? = null

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }

    /** The harness measures extensions without the app's WebView, so a challenge fails its source. */
    private object NoBrowser : ChallengeBrowser {
        override fun mayWait() = false

        override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean) = false
    }
}
