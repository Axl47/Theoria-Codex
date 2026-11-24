package com.theoriacodex.extensionhost

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExtensionLoaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val packages = ExtensionPackages(context, listOf(MihonKind))
    private val loader = ExtensionLoader(context, packages)
    private val host = HostServices(ExtensionImages())
    private val extension = ExtensionPackage("app.ext", "Ext", "1.6.1", 1, "key", File("/app.ext.apk"), ExtensionOrigin.PACKAGE, MihonKind, emptyList())

    private fun create(type: Class<*>) = loader.createSources(javaClass.classLoader!!, type.name, extension, host)

    @Test
    fun `a virtual machine error while an extension is created ends the load, and any other throwable rejects only that extension (ADR 0060)`() {
        listOf(
            OutOfMemoryFactory::class.java to OutOfMemoryError::class.java,
            InternalErrorFactory::class.java to InternalError::class.java,
            OutOfMemoryConstructor::class.java to OutOfMemoryError::class.java,
        ).forEach { (factory, error) ->
            assertEquals(error, assertFailsWith<VirtualMachineError> { create(factory) }.javaClass)
        }
        listOf(
            AssertionFactory::class.java to AssertionError::class.java,
            MissingClassFactory::class.java to NoClassDefFoundError::class.java,
            ThrowableFactory::class.java to Throwable::class.java,
        ).forEach { (factory, error) ->
            assertEquals(error, assertFailsWith<ExtensionRejectedException> { create(factory) }.cause?.javaClass)
        }
    }

    @Test
    fun `an instance that is neither a source nor a factory, or whose constructor throws, rejects the extension (ADR 0056)`() {
        val notASource = assertFailsWith<ExtensionRejectedException> { create(NotASource::class.java) }
        val throwing = assertFailsWith<ExtensionRejectedException> { create(ThrowingConstructor::class.java) }

        assertEquals("${NotASource::class.java.name} in app.ext is not a source of its kind", notASource.message)
        assertTrue(throwing.message.orEmpty().startsWith("${ThrowingConstructor::class.java.name} in app.ext could not be created"))
    }

    @Test
    fun `a private copy that no longer matches its listing is deleted and refused before any code loads (ADR 0063)`() {
        val apk = File(context.filesDir, "app.ext.apk").apply { writeText("not the listed archive") }
        val listed = extension.copy(apk = apk, origin = ExtensionOrigin.PRIVATE, sourceClasses = listOf(NotASource::class.java.name))

        val refused = assertFailsWith<ExtensionRejectedException> { loader.load(listed, host) }

        assertEquals("app.ext.apk changed after it was listed", refused.message)
        assertTrue(File(context.codeCacheDir, "mihon-extensions").listFiles().orEmpty().isEmpty())
    }
}

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

class NotASource

class ThrowingConstructor {
    init {
        error("extension")
    }
}
