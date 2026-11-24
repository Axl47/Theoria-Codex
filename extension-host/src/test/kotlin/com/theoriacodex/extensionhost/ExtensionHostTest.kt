package com.theoriacodex.extensionhost

import android.app.Application
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowser
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import eu.kanade.tachiyomi.source.sourcePreferences
import java.io.File
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import uy.kohesive.injekt.Injekt

@RunWith(RobolectricTestRunner::class)
class ExtensionHostTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `extensions get the app's own application, except that a source's preferences are its own no-backup file (ADR 0057, 0061)`() {
        ExtensionHost.install(app, "1.0", 1, NoCookies, ChallengeBrowsers(NoCookies, NoBrowser), HostOutageTracker())
        val handed = Injekt.getInstance<Application>(Application::class.java)

        ConfigurableStub {}.sourcePreferences().edit().putString("address", "https://server.test").commit()

        assertEquals(listOf(app.packageName, app.filesDir), listOf(handed.packageName, handed.filesDir))
        assertEquals("https://server.test", FilePreferences(ExtensionHost.preferencesFile(app, "source_7")).getString("address", null))
        assertFalse(File(app.dataDir, "shared_prefs").list().orEmpty().any { it.startsWith("source_7") })
    }

    @Test
    fun `the image key is made once where Android neither backs up nor transfers it, and replaced when unreadable (ADR 0059)`() {
        val file = File(app.noBackupFilesDir, "extension_image_key")

        val key = ExtensionHost.imageKey(app)

        assertEquals(32, key.size)
        assertArrayEquals(key, file.takeIf(File::exists)?.readBytes())
        assertArrayEquals(key, ExtensionHost.imageKey(app))
        file.writeBytes(ByteArray(5))
        val replaced = ExtensionHost.imageKey(app)
        assertEquals(32, replaced.size)
        assertFalse(replaced.contentEquals(key))
        assertArrayEquals(replaced, file.readBytes())
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
