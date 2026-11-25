package com.theoriacodex.extensionhost

import android.app.Application
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.ChallengeBrowser
import com.theoriacodex.sources.http.ChallengeBrowsers
import com.theoriacodex.sources.http.HostOutageTracker
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.lang.reflect.InvocationTargetException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExtensionLoaderTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var read: (File) -> ExtensionPackage? = { null }
    private val loader = loader("loader-test", 1)
    private val host = HostServices(ExtensionImages())
    private val extension = ExtensionPackage("app.ext", "Ext", "1.6.1", 1, "key", File("/app.ext.apk"), ExtensionOrigin.PACKAGE, MihonKind, emptyList())

    private fun loader(versionName: String, versionCode: Int) =
        ExtensionLoader.installingHost(app, versionName, versionCode, NoCookies, ChallengeBrowsers(NoCookies, NoBrowser), HostOutageTracker()) { read(it) }

    private fun create(type: Class<*>, kind: ExtensionKind = MihonKind) =
        loader.sources(javaClass.classLoader!!, extension.copy(kind = kind, sourceClasses = listOfNotNull(LoadableSource::class.java.name.takeIf { kind == MihonKind }, type.name)), host)

    private fun copies() = File(app.codeCacheDir, "mihon-extensions").listFiles().orEmpty().toList()

    @Test
    fun `any throwable while an extension is created, a virtual machine error included, rejects only that extension (ADR 0060)`() {
        listOf(
            OutOfMemoryFactory::class.java to OutOfMemoryError::class.java,
            InternalErrorFactory::class.java to InternalError::class.java,
            AssertionFactory::class.java to AssertionError::class.java,
            MissingClassFactory::class.java to NoClassDefFoundError::class.java,
            ThrowableFactory::class.java to Throwable::class.java,
            OutOfMemoryConstructor::class.java to InvocationTargetException::class.java,
            OutOfMemoryInitializer::class.java to ExceptionInInitializerError::class.java,
            FailingInitializer::class.java to ExceptionInInitializerError::class.java,
        ).forEach { (factory, error) ->
            assertEquals(error, assertFailsWith<ExtensionRejectedException> { create(factory) }.cause?.javaClass)
        }
    }

    @Test
    fun `an instance that is neither a source nor a factory, or whose constructor throws, rejects the extension (ADR 0056)`() {
        listOf(MihonKind, AniyomiKind).forEach { kind ->
            val notASource = assertFailsWith<ExtensionRejectedException> { create(NotASource::class.java, kind) }
            assertEquals("${NotASource::class.java.name} in app.ext is not a source of its kind", notASource.message)
        }
        val throwing = assertFailsWith<ExtensionRejectedException> { create(ThrowingConstructor::class.java) }

        assertTrue(throwing.message.orEmpty().startsWith("${ThrowingConstructor::class.java.name} in app.ext could not be created"))
        assertEquals(listOf("custom_mihon_3"), loader.sources(javaClass.classLoader!!, extension.copy(sourceClasses = listOf(LoadableSource::class.java.name)), host).map { it.key.id })
    }

    @Test
    fun `a private or debug copy loads only while its package, signer, version and size match the listing, locked, and any other copy is deleted before code loads (ADR 0063)`() {
        val apk = File(app.filesDir, "app.ext.apk").apply { writeText("archive") }
        listOf(ExtensionOrigin.PRIVATE, ExtensionOrigin.FILE).forEach { origin ->
            val listed = extension.copy(apk = apk, origin = origin, size = 7)
            val changes: List<(ExtensionPackage) -> ExtensionPackage?> = listOf(
                { null },
                { it.copy(packageName = "app.other") },
                { it.copy(signer = "other") },
                { it.copy(versionCode = 2) },
                { it.copy(size = 8) },
            )
            changes.forEach { change ->
                read = { copy -> change(listed.copy(apk = copy)) }
                assertEquals("app.ext.apk changed after it was listed", assertFailsWith<ExtensionRejectedException> { loader.load(listed, host) }.message)
                assertEquals(emptyList<File>(), copies())
            }
            read = { copy -> listed.copy(apk = copy, signer = null) }
            assertFailsWith<ExtensionRejectedException> { loader.load(listed.copy(signer = null), host) }
            assertEquals(emptyList<File>(), copies())

            read = { copy -> listed.copy(apk = copy) }
            assertEquals(emptyList<HostedSource>(), loader.load(listed, host))
            val kept = copies().single()
            assertFalse(kept.canWrite())
            kept.setWritable(true)
            kept.delete()
        }
    }

    @Test
    fun `an installed package loads from Android's own copy, and no copy is made (ADR 0063)`() {
        read = { null }

        assertEquals(emptyList<HostedSource>(), loader.load(extension, host))
        assertEquals(emptyList<File>(), copies())
    }

    @Test
    fun `the only way to get a loader installs the host first (ADR 0062)`() {
        loader("loader-installs", 7)

        assertEquals("loader-installs" to 7, AppInfo.getVersionName() to AppInfo.getVersionCode())
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

internal class LoadableSource : StubSource(3, "en")

class OutOfMemoryFactory : SourceFactory {
    override fun createSources(): List<Source> = throw OutOfMemoryError("extension")
}

class InternalErrorFactory : SourceFactory {
    override fun createSources(): List<Source> = throw InternalError("extension")
}

class AssertionFactory : SourceFactory {
    override fun createSources(): List<Source> = throw AssertionError("extension")
}

class MissingClassFactory : SourceFactory {
    override fun createSources(): List<Source> = throw NoClassDefFoundError("eu/kanade/tachiyomi/Missing")
}

class ThrowableFactory : SourceFactory {
    override fun createSources(): List<Source> = throw Throwable("extension")
}

class OutOfMemoryConstructor {
    init {
        throw OutOfMemoryError("extension")
    }
}


class OutOfMemoryInitializer {
    companion object {
        init {
            OutOfMemoryConstructor::class.java.getDeclaredConstructor().newInstance()
        }
    }
}

class FailingInitializer {
    companion object {
        init {
            error("extension")
        }
    }
}

class NotASource

class ThrowingConstructor {
    init {
        error("extension")
    }
}
