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

internal class Harness(private val tag: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    fun apkNamed(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    fun load(apkName: String, services: HostServices): Pair<ExtensionPackage, List<HostedSource>> {
        val installed = ExtensionHost.install(context.applicationContext as Application, versionName = "harness", versionCode = 1, cookies = NoCookies, browsers = ChallengeBrowsers(NoCookies, NoBrowser), outages = HostOutageTracker())
        val packages = ExtensionPackages(context, listOf(MihonKind, AniyomiKind))
        val extension = checkNotNull(packages.read(File(context.getExternalFilesDir(null), apkName))) { "$apkName is not an extension" }
        val sources = timed("load") { installed.loader(context, packages).load(extension, services) }
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

    fun report(key: String, message: String) {
        Log.i(tag, "$key: $message")
        instrumentation.sendStatus(0, Bundle().apply { putString("harness", "$key: $message") })
    }

    private object NoCookies : BrowserCookieStore {
        override fun cookieHeader(url: String): String? = null

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }

    private object NoBrowser : ChallengeBrowser {
        override fun mayWait() = false

        override fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean) = false
    }
}
