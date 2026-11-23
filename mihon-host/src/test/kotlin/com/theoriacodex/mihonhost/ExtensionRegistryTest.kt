package com.theoriacodex.mihonhost

import androidx.preference.PreferenceScreen
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRegistryTest {
    private var onDevice = listOf(extension("a", signer = "key-1"))
    private val loads = mutableListOf<String>()
    private var sourcesOf: (ExtensionPackage) -> List<Source> = { listOf(StubSource(1, "en")) }
    private var deviceLanguages = listOf("en-US")
    private val store = object : ExtensionChoiceStore {
        var choices = ExtensionChoices()

        /** Thrown by the next [choices] in place of an answer, once. */
        var interruption: Throwable? = null

        override suspend fun choices() = interruption?.let { interruption = null; throw it } ?: choices

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
            choices = transform(choices)
        }
    }
    private val images = ExtensionImages()
    private val registry = registry { error("No source here has settings") }

    /** How many times the registry listed the device. */
    private var listings = 0

    private fun registry(settings: (ConfigurableSource) -> SourceSettings) = ExtensionRegistry(
        packages = { listings += 1; onDevice },
        load = { loads += it.apk.path; sourcesOf(it) },
        choices = store,
        images = images,
        settings = settings,
        deviceLanguages = { deviceLanguages },
        limitMs = 500,
    )

    /** Whether an image address tagged with [source] is answered by that source instead of passing down the chain. */
    private fun fetchesImages(source: StubHttpSource): Boolean {
        val client = OkHttpClient.Builder().addInterceptor(images).addInterceptor(answering { cannedResponse(it, body = "app") }).build()
        val series = Series(SeriesId(MihonSeriesSource.sourceKey(source), "/s"), "Series", coverUrl = "https://covers.test/1.jpg")
        return client.newCall(Request.Builder().url(checkNotNull(images.address(series))).build()).execute().body.string() == "source"
    }

    /** Extensions a and b, both on, offering sources 1 and 2. */
    private fun twoExtensionsOn() {
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"))
        store.choices = store.choices.copy(trusted = mapOf("a" to "key-1", "b" to "key-1"))
        sourcesOf = { listOf(StubSource(if (it.packageName == "a") 1 else 2, "en")) }
    }

    @Test
    fun `a lookup loads only the extension that last offered its source`() = runBlocking {
        twoExtensionsOn()
        // A first start knows no owners, so the lookup loads every extension that is on and keeps who offers what.
        assertEquals("custom_mihon_2", registry.source(MihonSeriesSource.sourceKey(2))?.key?.id)
        assertEquals(listOf("/a.apk", "/b.apk"), loads)
        assertEquals(mapOf("custom_mihon_1" to "a", "custom_mihon_2" to "b"), store.choices.owners)

        loads.clear()
        val restarted = registry { error("No source here has settings") }
        assertEquals("custom_mihon_2", restarted.source(MihonSeriesSource.sourceKey(2))?.key?.id)
        assertEquals(listOf("/b.apk"), loads)
        // Loading the rest for the whole list changes nothing anyone was handed, so the revision stays.
        val revision = restarted.revision.value
        assertEquals(listOf("custom_mihon_1", "custom_mihon_2"), restarted.sources().map { it.key.id })
        assertEquals(revision, restarted.revision.value)
        assertEquals(listOf("/b.apk", "/a.apk"), loads)
    }

    @Test
    fun `a source its recorded extension no longer offers is found by loading every extension, once`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_2" to "a"))

        assertEquals("custom_mihon_2", registry.source(MihonSeriesSource.sourceKey(2))?.key?.id)
        assertEquals(listOf("/a.apk", "/b.apk"), loads)
        assertEquals("b", store.choices.owners["custom_mihon_2"])
        // Everything that is on has loaded: a source none offers is answered without listing the device again.
        val before = listings
        assertNull(registry.source(MihonSeriesSource.sourceKey(9)))
        assertEquals(before, listings)
    }

    @Test
    fun `a source whose recorded extension is off is not found, and the others do not load for it`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(trusted = mapOf("a" to "key-1"), owners = mapOf("custom_mihon_2" to "b"))

        assertNull(registry.source(MihonSeriesSource.sourceKey(2)))
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `an extension that is off is listed without running its code`() = runBlocking {
        val listed = registry.installed().single()

        assertEquals(listOf("a 1.0 key-1", false), listOf("${listed.name} ${listed.versionName} ${listed.signer}", listed.on))
        assertTrue(listed.languages.isEmpty())
        assertTrue(registry.sources().isEmpty())
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `turning an extension on loads it once and offers its sources`() = runBlocking {
        registry.setOn("/a.apk", "key-1", true)

        assertEquals(mapOf("a" to "key-1"), store.choices.trusted)
        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        assertTrue(registry.installed().single().on)
        assertEquals(listOf("/a.apk"), loads)
        assertEquals(1, registry.revision.value)

        registry.setOn("/a.apk", "key-1", false)

        assertTrue(registry.sources().isEmpty())
        assertFalse(registry.installed().single().on)
    }

    @Test
    fun `a host whose challenge stopped a source's call is traced to that source from then on`() = runBlocking {
        sourcesOf = {
            listOf(
                object : StubSource(1, "en") {
                    override suspend fun getPopularManga(page: Int): MangasPage =
                        throw HostUnavailableException("api.elsewhere.test", HostOutageKind.CHALLENGE)
                },
            )
        }
        registry.setOn("/a.apk", "key-1", true)
        val source = registry.sources().single()
        assertTrue(registry.sites().isEmpty())

        runCatching { source.popular(1) }

        assertEquals(mapOf(source.key to ExtensionSite("Stub", setOf("api.elsewhere.test"))), registry.sites())
        assertEquals(mapOf(source.key to setOf("api.elsewhere.test")), registry.siteChanges.value)
    }

    @Test
    fun `turning one extension on leaves another with the same key off`() = runBlocking {
        // Another package, and a file of the same extension in the debug folder: each is turned on by itself.
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"), extension("a", signer = "key-1", apk = "/files/a.apk", installed = false))

        val listed = registry.setOn("/a.apk", "key-1", true)

        assertEquals(listOf(true, false, false), listed.map { it.on })
        assertEquals(listOf("/a.apk"), loads)

        registry.setOn(listed[2].id, "key-1", true)
        assertEquals(listOf(true, false, true), registry.setOn("/b.apk", "key-1", false).map { it.on })
    }

    @Test
    fun `an extension removed, replaced or installed later is picked up by the next recheck`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1", "b" to "key-1"))
        val first = registry.sources().single()
        assertTrue(registry.sources().single() === first)
        assertEquals(1, registry.revision.value)

        onDevice = emptyList()
        registry.recheck()
        assertTrue(registry.sources().isEmpty())
        assertEquals(2, registry.revision.value)

        onDevice = listOf(extension("a", signer = "key-1").copy(modified = 2))
        registry.recheck()
        assertTrue(registry.sources().single() !== first)

        sourcesOf = { listOf(StubSource(if (it.name == "a") 1 else 2, "en")) }
        onDevice = onDevice + extension("b", signer = "key-1")
        registry.recheck()
        assertEquals(listOf("custom_mihon_1", "custom_mihon_2"), registry.sources().map { it.key.id })
        // Only what changed was loaded each time.
        assertEquals(listOf("/a.apk", "/a.apk", "/b.apk"), loads)
        assertEquals(4, registry.revision.value)
    }

    @Test
    fun `an extension that is off moves the revision when it is installed or removed, for the page that lists it`() = runBlocking {
        registry.installed()
        val listed = registry.revision.value

        onDevice = emptyList()
        registry.recheck()

        assertEquals(listed + 1, registry.revision.value)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `once loaded, lookups ask nothing of the device, and a recheck before the first load lists nothing`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        registry.recheck()
        assertEquals(0, listings)

        registry.sources()
        onDevice = emptyList()
        repeat(3) { assertEquals(1, registry.sources().size) }

        assertEquals(1, listings)
    }

    @Test
    fun `a recheck tries again a first load that failed`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        store.interruption = IllegalStateException("the choices could not be read")
        assertFailsWith<IllegalStateException> { registry.sources() }

        registry.recheck()

        assertEquals(2, listings)
        assertEquals(1, registry.sources().size)
        assertEquals(2, listings)
    }

    @Test
    fun `another app updating leaves the revision where it was`() = runBlocking {
        registry.installed()
        val listed = registry.revision.value

        registry.recheck()

        assertEquals(listed, registry.revision.value)
    }

    @Test
    fun `a recheck interrupted after a change on the device leaves the change for the next one`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        registry.sources()

        onDevice = emptyList()
        store.interruption = CancellationException("the caller left")
        assertFailsWith<CancellationException> { registry.recheck() }
        assertEquals(1, registry.sources().size)

        registry.recheck()
        assertTrue(registry.sources().isEmpty())
    }

    @Test
    fun `another extension put under a folder file's name with the same key is not the one asked about or turned on`() = runBlocking {
        val first = listOf(extension("a", signer = "key-1", apk = "/x.apk", installed = false))
        val other = listOf(extension("b", signer = "key-1", apk = "/x.apk", installed = false))
        onDevice = first
        val asked = registry.installed().single().id

        onDevice = other
        assertFalse(registry.setOn(asked, "key-1", true).single().on)
        onDevice = first
        assertTrue(registry.setOn(asked, "key-1", true).single().on)
        onDevice = other
        assertFalse(registry.installed().single().on)
        assertEquals(1, loads.size)
    }

    @Test
    fun `an update signed with the same key stays on and a copy signed with another key is off`() = runBlocking {
        registry.setOn("/a.apk", "key-1", true)

        onDevice = listOf(extension("a", signer = "key-1", apk = "/a-2.apk"))
        assertTrue(registry.installed().single().on)
        assertEquals(listOf("/a.apk", "/a-2.apk"), loads)

        onDevice = listOf(extension("a", signer = "key-2", apk = "/a-3.apk"))
        assertFalse(registry.installed().single().on)
        assertTrue(registry.sources().isEmpty())
        assertEquals(listOf("/a.apk", "/a-2.apk"), loads)
    }

    @Test
    fun `a file replaced while the user was asked is not turned on`() = runBlocking {
        val shown = registry.installed().single().signer!!
        onDevice = listOf(extension("a", signer = "key-2"))

        val listed = registry.setOn("/a.apk", shown, true).single()

        assertEquals(listOf<Any?>("key-2", false), listOf(listed.signer, listed.on))
        assertTrue(store.choices.trusted.isEmpty())
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `a file replaced in place loads anew and its old code is no longer offered`() = runBlocking {
        registry.setOn("/a.apk", "key-1", true)
        val before = registry.sources().single()

        // The same path and signer, other content.
        onDevice = listOf(extension("a", signer = "key-1").copy(modified = 2))
        registry.installed()

        assertEquals(listOf("/a.apk", "/a.apk"), loads)
        assertTrue(registry.sources().single() !== before)
    }

    @Test
    fun `a source fetches images only while its extension and language are on`() = runBlocking {
        val english = StubHttpSource(1, "en")
        val spanish = StubHttpSource(2, "es")
        sourcesOf = { listOf(english, spanish) }

        registry.setOn("/a.apk", "key-1", true)
        assertEquals(listOf(true, false), listOf(english, spanish).map(::fetchesImages))

        registry.setOn("/a.apk", "key-1", false)
        assertEquals(listOf(false, false), listOf(english, spanish).map(::fetchesImages))
    }

    @Test
    fun `settings are built only for the extension asked about and an edit for one turned off does nothing`() = runBlocking {
        val built = mutableListOf<String>()
        val configurable = ConfigurableStub {}
        sourcesOf = { listOf(configurable) }
        val registry = registry { source ->
            built += source.name
            error("Stops at the build")
        }

        registry.setOn("/a.apk", "key-1", true)
        registry.sources()
        assertTrue(built.isEmpty())

        registry.settings("/a.apk")
        assertEquals(1, built.size)

        registry.setOn("/a.apk", "key-1", false)
        registry.change("/a.apk", "custom_mihon_7", ExtensionSetting.Toggle("k", "", null, enabled = true, on = true))
        assertEquals(1, built.size)
    }

    @Test
    fun `an extension without a signing certificate cannot be turned on`() = runBlocking {
        onDevice = listOf(extension("a", signer = null))
        // Even a store that somehow holds an empty fingerprint trusts nothing.
        store.choices = ExtensionChoices(trusted = mapOf("a" to ""))

        registry.setOn("/a.apk", "key-1", true)
        val listed = registry.installed().single()

        assertFalse(listed.on)
        assertEquals("Not signed", listed.problem)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `only the device's language is on until the user chooses`() = runBlocking {
        sourcesOf = { listOf(StubSource(1, "en"), StubSource(2, "es"), StubSource(3, "ko")) }
        deviceLanguages = listOf("es-PR")

        registry.setOn("/a.apk", "key-1", true)

        assertEquals(listOf("custom_mihon_2"), registry.sources().map { it.key.id })
        assertEquals(listOf("en" to false, "es" to true, "ko" to false), registry.installed().single().languages.map { it.code to it.on })

        registry.setLanguage("/a.apk", "ko", true)
        registry.setLanguage("/a.apk", "es", false)

        assertEquals(listOf("custom_mihon_3"), registry.sources().map { it.key.id })
        assertEquals(mapOf("a" to setOf("ko")), store.choices.languages)
        assertEquals(listOf(ExtensionLanguage("en", false), ExtensionLanguage("es", false), ExtensionLanguage("ko", true)), registry.installed().single().languages)
    }

    @Test
    fun `an extension that hangs or fails is named and the others still load`() = runBlocking {
        val never = CountDownLatch(1)
        onDevice = listOf(extension("hangs", signer = "key-1"), extension("fails", signer = "key-1"), extension("works", signer = "key-1"))
        sourcesOf = { extension ->
            when (extension.name) {
                "hangs" -> emptyList<Source>().also { never.await() }
                "fails" -> throw NoClassDefFoundError("androidx/missing/Thing")
                else -> listOf(StubSource(1, "en"))
            }
        }

        listOf("hangs", "fails", "works").forEach { registry.setOn("/$it.apk", "key-1", true) }
        val listed = registry.installed().associate { it.name to it.problem }

        assertEquals(mapOf("hangs" to "Took longer than 0 s", "fails" to "androidx/missing/Thing", "works" to null), listed)
        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        never.countDown()
    }

    @Test
    fun `the same extension on the device twice offers its sources once`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1"), extension("a", signer = "key-1", apk = "/files/a.apk"))

        registry.setOn("/a.apk", "key-1", true)

        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        assertNull(registry.installed().last().problem)
    }

    private fun extension(name: String, signer: String?, apk: String = "/$name.apk", installed: Boolean = true) =
        ExtensionPackage(packageName = name, name = name, versionName = "1.0", versionCode = 1, signer = signer, apk = File(apk), installed = installed, sourceClasses = listOf(".Source"))
}

/** A source that is only ever named, never asked for anything. */
internal open class StubSource(override val id: Long, override val lang: String) : Source {
    override val name = "Stub"
    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = error("Not asked in these tests")

    override suspend fun getLatestUpdates(page: Int): MangasPage = error("Not asked in these tests")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = error("Not asked in these tests")

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate =
        error("Not asked in these tests")

    override suspend fun getPageList(chapter: SChapter): List<Page> = error("Not asked in these tests")
}

/** A source with a client of its own, which answers every image request with "source". */
internal class StubHttpSource(override val id: Long, override val lang: String) : HttpSource() {
    override val name = "Stub"
    override val baseUrl = "https://stub.test"
    override val supportsLatest = false
    override val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(answering { cannedResponse(it, body = "source") }).build()

    // The default asks the host for its browser agent string, which these tests do not install.
    override fun headersBuilder() = Headers.Builder()
}

/** A source whose settings screen is [setup]. */
internal class ConfigurableStub(private val setup: (PreferenceScreen) -> Unit) : StubSource(7, "en"), ConfigurableSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) = setup(screen)
}
