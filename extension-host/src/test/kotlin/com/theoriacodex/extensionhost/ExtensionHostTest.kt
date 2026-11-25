package com.theoriacodex.extensionhost

import android.app.Application
import android.content.Context
import com.theoriacodex.sources.http.BrowserAddressDns
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowser
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.sourcePreferences
import java.io.File
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uy.kohesive.injekt.Injekt

@RunWith(RobolectricTestRunner::class)
class ExtensionHostTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `extensions get the app's own application and context, except that every preferences name is a no-backup file of the host's (ADR 0057, 0061)`() {
        ExtensionHost.install(app, "1.0", 1, NoCookies, ChallengeBrowsers(NoCookies, NoBrowser), HostOutageTracker())
        val handed = Injekt.getInstance<Application>(Application::class.java)

        ConfigurableStub {}.sourcePreferences().edit().putString("address", "https://server.test").commit()
        Injekt.getInstance<Context>(Context::class.java).getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putString("theme", "dark").commit()

        assertEquals(listOf(app.packageName, app.filesDir), listOf(handed.packageName, handed.filesDir))
        assertEquals("https://server.test", FilePreferences(File(app.noBackupFilesDir, "mihon_preferences/source_7.json")).getString("address", null))
        assertEquals("dark", FilePreferences(File(app.noBackupFilesDir, "mihon_preferences/app_settings.json")).getString("theme", null))
        assertEquals(emptyList<String>(), File(app.dataDir, "shared_prefs").list().orEmpty().filter { it.startsWith("source_7") || it.startsWith("app_settings") })
    }

    @Test
    fun `extensions are handed the extension client, with Mihon's interceptors and timeouts, the browser's cookies for every host and its DNS (ADR 0056, 0057)`() {
        val saved = mutableListOf<String>()
        val cookies = object : BrowserCookieStore by NoCookies {
            override fun cookieHeader(url: String) = "session=abc"

            override fun setCookie(url: String, setCookie: String) {
                saved += setCookie
            }
        }
        ExtensionHost.install(app, "1.0", 1, cookies, ChallengeBrowsers(NoCookies, NoBrowser), HostOutageTracker())

        val client = Injekt.getInstance<NetworkHelper>(NetworkHelper::class.java).client

        assertEquals(listOf("UncaughtExceptionInterceptor", "UserAgentInterceptor", "CloudflareInterceptor"), client.interceptors.map { it.javaClass.simpleName })
        assertEquals(listOf(30_000, 30_000, 120_000), listOf(client.connectTimeoutMillis, client.readTimeoutMillis, client.callTimeoutMillis))
        assertEquals(listOf("session=abc"), client.cookieJar.loadForRequest("https://api.any-site.test/list".toHttpUrl()).map { "${it.name}=${it.value}" })
        assertTrue(client.dns is BrowserAddressDns)
        val answered = "https://api.any-site.test/list".toHttpUrl()
        client.cookieJar.saveFromResponse(answered, listOfNotNull(Cookie.parse(answered, "token=xyz; Path=/")))
        assertTrue(saved.single().startsWith("token=xyz"))
    }

    @Test
    fun `the image key is made once where Android neither backs up nor transfers it, and replaced when unreadable (ADR 0059)`() {
        val file = File(app.noBackupFilesDir, "extension_image_key")

        val key = ExtensionHost.imageKey(app)

        assertEquals(32, key.size)
        assertArrayEquals(key, file.takeIf(File::exists)?.readBytes())
        assertArrayEquals(key, ExtensionHost.imageKey(app))
        file.writeBytes(ByteArray(33))
        assertEquals(32, ExtensionHost.imageKey(app).size)
        assertEquals(32, file.readBytes().size)
        file.writeBytes(ByteArray(5))
        val replaced = ExtensionHost.imageKey(app)
        assertEquals(32, replaced.size)
        assertFalse(replaced.contentEquals(key))
        assertArrayEquals(replaced, file.readBytes())
        file.delete()
        file.mkdir()
        assertEquals(32, ExtensionHost.imageKey(app).size)
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
