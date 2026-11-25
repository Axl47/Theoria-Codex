package com.theoriacodex.extensionhost

import androidx.preference.PreferenceScreen
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionLanguage
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
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
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRegistryTest {
    private var onDevice = listOf(extension("a", signer = "key-1"))
    private val loads = mutableListOf<String>()
    private var sourcesOf: (ExtensionPackage) -> List<Any> = { listOf(StubSource(1, "en")) }
    private var deviceLanguages = listOf("en-US")
    private val store = object : ExtensionChoiceStore {
        var choices = ExtensionChoices()

        var interruption: Throwable? = null

        override suspend fun choices() = interruption?.let { interruption = null; throw it } ?: choices

        var beforeUpdate: suspend () -> Unit = {}

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
            beforeUpdate()
            choices = transform(choices)
        }
    }
    private val images = ExtensionImages()
    private val registry = registry { error("No source here has settings") }

    private var listings = 0

    private fun registry(settings: (SettingsHook) -> SourceSettings) = ExtensionRegistry(
        packages = { listings += 1; onDevice.toList() },
        load = { extension, host -> loads += extension.apk.path; hosted(extension, host) },
        choices = store,
        images = images,
        settings = settings,
        deviceLanguages = { deviceLanguages },
        limitMs = 500,
    )

    private fun fetchesImages(source: StubHttpSource): Boolean {
        val client = OkHttpClient.Builder().addInterceptor(images).addInterceptor(answering { cannedResponse(it, body = "app") }).build()
        val series = Series(SeriesId(MihonKind.sourceKey(source.id), "/s"), "Series", coverUrl = "https://covers.test/1.jpg")
        return client.newCall(Request.Builder().url(checkNotNull(images.address(series))).build()).execute().body.string() == "source"
    }

    private fun twoExtensionsOn() {
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"))
        store.choices = store.choices.copy(trusted = mapOf("a" to "key-1", "b" to "key-1"))
        sourcesOf = { listOf(StubSource(if (it.packageName == "a") 1 else 2, "en")) }
    }

    @Test
    fun `a lookup loads only the extension that last offered its source (ADR 0007, 0062)`() = runBlocking {
        twoExtensionsOn()
        assertEquals("custom_mihon_2", registry.source(MihonKind.sourceKey(2))?.key?.id)
        assertEquals(listOf("/a.apk", "/b.apk"), loads)
        assertEquals(mapOf("custom_mihon_1" to "a", "custom_mihon_2" to "b"), store.choices.owners)

        loads.clear()
        val restarted = registry { error("No source here has settings") }
        assertEquals("custom_mihon_2", restarted.source(MihonKind.sourceKey(2))?.key?.id)
        assertEquals(listOf("/b.apk"), loads)
        val revision = restarted.revision.value
        assertEquals(listOf("custom_mihon_1", "custom_mihon_2"), restarted.sources().map { it.key.id })
        assertEquals(revision, restarted.revision.value)
        assertEquals(listOf("/b.apk", "/a.apk"), loads)
    }

    @Test
    fun `a source its recorded extension no longer offers is found by loading every extension, once (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_2" to "a"))

        assertEquals("custom_mihon_2", registry.source(MihonKind.sourceKey(2))?.key?.id)
        assertEquals(listOf("/a.apk", "/b.apk"), loads)
        assertEquals("b", store.choices.owners["custom_mihon_2"])
        val before = listings
        assertNull(registry.source(MihonKind.sourceKey(9)))
        assertEquals(before, listings)
    }

    @Test
    fun `a source whose recorded extension is off is not found, and the others do not load for it (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(trusted = mapOf("a" to "key-1"), owners = mapOf("custom_mihon_2" to "b"))

        assertNull(registry.source(MihonKind.sourceKey(2)))
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `a source whose recorded extension left the device is found under the one that offers it now (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_2" to "renamed-away"))

        assertEquals("custom_mihon_2", registry.source(MihonKind.sourceKey(2))?.key?.id)
        assertEquals("b", store.choices.owners["custom_mihon_2"])
    }

    @Test
    fun `a lookup cancelled while it keeps the owners still offers what it loaded (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_1" to "a"))
        assertNotNull(registry.source(MihonKind.sourceKey(1)))
        val updating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeUpdate = { updating.complete(Unit); release.await() }

        val lookup = launch(Dispatchers.Default) { registry.source(MihonKind.sourceKey(2)) }
        withTimeout(5_000) { updating.await() }
        lookup.cancel()
        release.complete(Unit)
        lookup.join()

        assertEquals("b", store.choices.owners["custom_mihon_2"])
        assertEquals(listOf("custom_mihon_1", "custom_mihon_2"), registry.loadedSources().map { it.key.id })
        assertEquals("custom_mihon_2", registry.source(MihonKind.sourceKey(2))?.key?.id)
    }

    @Test
    fun `a source whose recorded extension has its language off is not found, and the others do not load for it (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_2" to "b"), languages = mapOf("b" to setOf("fr")))

        assertNull(registry.source(MihonKind.sourceKey(2)))
        assertEquals(listOf("/b.apk"), loads)
    }

    @Test
    fun `a failure keeping which extension offers each source fails no lookup, and the next start loads everything (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        store.beforeUpdate = { throw IllegalStateException("The choices could not be written") }

        assertEquals("custom_mihon_2", registry.source(MihonKind.sourceKey(2))?.key?.id)
        store.beforeUpdate = {}
        loads.clear()
        assertEquals("custom_mihon_2", registry { error("No source here has settings") }.source(MihonKind.sourceKey(2))?.key?.id)

        assertEquals(listOf("/a.apk", "/b.apk"), loads)
    }

    @Test
    fun `simultaneous cold lookups list the device and load the extension once (ADR 0062)`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        sourcesOf = { Thread.sleep(100); listOf(StubSource(1, "en")) }

        val found = List(8) { async(Dispatchers.Default) { registry.source(MihonKind.sourceKey(1)) } }.awaitAll()

        assertEquals(List(8) { "custom_mihon_1" }, found.map { it?.key?.id })
        assertEquals(1, listings)
        assertEquals(listOf("/a.apk"), loads)
    }

    @Test
    fun `an anime source's lookup also loads only the extension that last offered it (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1").copy(kind = AniyomiKind), extension("c", signer = "key-1").copy(kind = AniyomiKind))
        sourcesOf = {
            when (it.packageName) {
                "a" -> listOf(StubSource(2, "en"))
                "b" -> listOf(StubAnimeSource(2, "en"))
                else -> listOf(StubAnimeSource(3, "en"))
            }
        }
        store.choices = store.choices.copy(trusted = store.choices.trusted + ("c" to "key-1"), owners = mapOf("custom_aniyomi_2" to "b"))

        assertEquals("custom_aniyomi_2", registry.source(AniyomiKind.sourceKey(2))?.key?.id)
        assertEquals(listOf("/b.apk"), loads)
    }

    @Test
    fun `an extension that is off is listed without running its code (ADR 0007, 0189)`() = runBlocking {
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
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"), extension("a", signer = "key-1", apk = "/files/a.apk", origin = ExtensionOrigin.FILE))

        val listed = registry.setOn("/a.apk", "key-1", true)

        assertEquals(listOf(true, false, false), listed.map { it.on })
        assertEquals(listOf("/a.apk"), loads)

        registry.setOn(listed[2].id, "key-1", true)
        assertEquals(listOf(true, false, true), registry.setOn("/b.apk", "key-1", false).map { it.on })
    }

    @Test
    fun `an extension removed, replaced or installed later is picked up by the next recheck (ADR 0062)`() = runBlocking {
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
        assertEquals(listOf("/a.apk", "/a.apk", "/b.apk"), loads)
        assertEquals(4, registry.revision.value)

        val loaded = registry.sources()
        onDevice = listOf(extension("a", signer = "key-1").copy(modified = 3), extension("b", signer = "key-1"))
        registry.recheck()
        assertTrue(registry.sources().first() !== loaded.first())
    }

    @Test
    fun `an extension that is off moves the revision when it is installed or removed, for the page that lists it (ADR 0062)`() = runBlocking {
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
    fun `a recheck tries again a first load that failed (ADR 0062)`() = runBlocking {
        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        store.interruption = IllegalStateException("the choices could not be read")
        assertFailsWith<IllegalStateException> { registry.sources() }

        registry.recheck()

        assertEquals(2, listings)
        assertEquals(1, registry.sources().size)
        assertEquals(2, listings)

        val cold = registry { error("No source here has settings") }
        store.interruption = IllegalStateException("the choices could not be read")
        assertFailsWith<IllegalStateException> { cold.source(MihonKind.sourceKey(1)) }
        cold.recheck()
        assertEquals(3, listings)
    }

    @Test
    fun `a recheck after single lookups lists the device again (ADR 0062)`() = runBlocking {
        twoExtensionsOn()
        onDevice = onDevice.take(1)
        store.choices = store.choices.copy(owners = mapOf("custom_mihon_1" to "a"))
        assertNotNull(registry.source(MihonKind.sourceKey(1)))
        val revision = registry.revision.value

        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"))
        registry.recheck()

        assertEquals(revision + 1, registry.revision.value)
    }

    @Test
    fun `another app updating leaves the revision where it was (ADR 0062)`() = runBlocking {
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
    fun `another extension put under a folder file's name with the same key is not the one asked about or turned on (ADR 0062, 0063)`() = runBlocking {
        val first = listOf(extension("a", signer = "key-1", apk = "/x.apk", origin = ExtensionOrigin.FILE))
        val other = listOf(extension("b", signer = "key-1", apk = "/x.apk", origin = ExtensionOrigin.FILE))
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
    fun `an update signed with the same key stays on and a copy signed with another key is off (ADR 0062)`() = runBlocking {
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
    fun `a file replaced while the user was asked is not turned on (ADR 0007, 0062)`() = runBlocking {
        val shown = registry.installed().single().signer!!
        onDevice = listOf(extension("a", signer = "key-2"))

        val listed = registry.setOn("/a.apk", shown, true).single()

        assertEquals(listOf<Any?>("key-2", false), listOf(listed.signer, listed.on))
        assertTrue(store.choices.trusted.isEmpty())
        assertTrue(loads.isEmpty())

        store.choices = ExtensionChoices(trusted = mapOf("a" to "key-1"))
        assertEquals(listOf<Any?>("key-2", false), registry.setOn("/a.apk", "key-1", true).single().let { listOf(it.signer, it.on) })
        assertEquals(mapOf("a" to "key-1"), store.choices.trusted)
    }

    @Test
    fun `a file replaced in place loads anew and its old code is no longer offered (ADR 0062, 0063)`() = runBlocking {
        registry.setOn("/a.apk", "key-1", true)
        val replacements = listOf<(ExtensionPackage) -> ExtensionPackage>({ it.copy(modified = 2) }, { it.copy(versionCode = 2) }, { it.copy(size = 2) })

        replacements.forEach { replace ->
            val before = registry.sources().single()
            onDevice = listOf(replace(extension("a", signer = "key-1")))
            registry.installed()
            assertTrue(registry.sources().single() !== before)
        }

        assertEquals(List(4) { "/a.apk" }, loads)
    }

    @Test
    fun `a source fetches images only while its extension and language are on (ADR 0059, 0062)`() = runBlocking {
        val english = StubHttpSource(1, "en")
        val spanish = StubHttpSource(2, "es")
        sourcesOf = { listOf(english, spanish) }

        registry.setOn("/a.apk", "key-1", true)
        assertEquals(listOf(true, false), listOf(english, spanish).map(::fetchesImages))

        registry.setOn("/a.apk", "key-1", false)
        assertEquals(listOf(false, false), listOf(english, spanish).map(::fetchesImages))
    }

    @Test
    fun `no settings are read during refresh, and an edit for an extension turned off does nothing (ADR 0062)`() = runBlocking {
        var built = 0
        val configurable = ConfigurableStub {}
        sourcesOf = { listOf(configurable) }
        val registry = registry {
            built++
            error("Stops at the build")
        }

        registry.setOn("/a.apk", "key-1", true)
        registry.sources()
        assertEquals(0, built)

        registry.settings("/a.apk")
        assertEquals(1, built)

        registry.setOn("/a.apk", "key-1", false)
        registry.change("/a.apk", "custom_mihon_7", ExtensionSetting.Toggle("k", "", null, enabled = true, on = true))
        assertEquals(1, built)
    }

    @Test
    fun `an extension without a signing certificate cannot be turned on (ADR 0007)`() = runBlocking {
        onDevice = listOf(extension("a", signer = null))
        store.choices = ExtensionChoices(trusted = mapOf("a" to ""))

        registry.setOn("/a.apk", "key-1", true)
        val listed = registry.installed().single()

        assertFalse(listed.on)
        assertEquals("Not signed", listed.problem)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `an extension its kind refuses says why and never loads, even when turned on`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1").copy(unsupported = "Streams by torrent"))

        registry.setOn("/a.apk", "key-1", true)
        val listed = registry.installed().single()

        assertEquals(listOf(true, "Streams by torrent"), listOf(listed.on, listed.problem))
        assertTrue(registry.sources().isEmpty())
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `each extension is listed with the kind of series its interface serves`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1").copy(kind = AniyomiKind))

        assertEquals(listOf(SeriesKind.MANGA, SeriesKind.ANIME), registry.installed().map { it.kind })
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
    fun `an extension that is off, or a language that is, is told apart from a source no extension offers (ADR 0062)`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1"), extension("b", signer = "key-1"), extension("c", signer = "key-1"))
        sourcesOf = { listOf(StubSource(it.packageName[0].code.toLong(), "en"), StubSource(it.packageName[0].code + 100L, "es")) }
        store.choices = store.choices.copy(
            trusted = mapOf("a" to "key-1"),
            languages = mapOf("a" to setOf("en")),
            owners = mapOf(MihonKind.sourceKey('b'.code.toLong()).id to "b", MihonKind.sourceKey('b'.code + 100L).id to "b"),
        )

        val availability = registry.availability()

        assertEquals(setOf(MihonKind.sourceKey('a'.code.toLong())), availability.on)
        assertEquals(
            mapOf(
                MihonKind.sourceKey('a'.code + 100L) to SourceOff("a", "es"),
                MihonKind.sourceKey('b'.code.toLong()) to SourceOff("b"),
                MihonKind.sourceKey('b'.code + 100L) to SourceOff("b"),
            ),
            availability.off,
        )
        assertNull(availability.off[MihonKind.sourceKey('c'.code.toLong())])
    }

    @Test
    fun `an extension that hangs or fails is named and the others still load (ADR 0060)`() = runBlocking {
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
    fun `a virtual machine error while an extension loads, thrown or wrapped, marks only that extension broken and the others still load (ADR 0060)`() = runBlocking {
        onDevice = listOf(extension("thrown", signer = "key-1"), extension("wrapped", signer = "key-1"), extension("works", signer = "key-1"))
        store.choices = ExtensionChoices(trusted = onDevice.associate { it.packageName to "key-1" })
        sourcesOf = { extension ->
            when (extension.name) {
                "thrown" -> throw OutOfMemoryError("extension")
                "wrapped" -> throw InvocationTargetException(OutOfMemoryError("extension"))
                else -> listOf(StubSource(1, "en"))
            }
        }

        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        assertEquals(
            mapOf("thrown" to "extension", "wrapped" to "java.lang.reflect.InvocationTargetException", "works" to null),
            registry.installed().associate { it.name to it.problem },
        )
    }

    @Test
    fun `the same extension on the device twice offers its sources once (ADR 0062)`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1"), extension("a", signer = "key-1", apk = "/files/a.apk"))
        sourcesOf = { listOf(object : StubSource(1, "en") { override val name = if (it.apk.path == "/a.apk") "First" else "Second" }) }

        registry.setOn("/a.apk", "key-1", true)

        assertEquals(listOf("custom_mihon_1" to "First"), registry.sources().map { it.key.id to it.name })
        assertNull(registry.installed().last().problem)
    }

    private fun hosted(extension: ExtensionPackage, host: HostServices): List<HostedSource> =
        sourcesOf(extension).flatMap { checkNotNull(extension.kind.adapt(it, host)) }

    private fun extension(name: String, signer: String?, apk: String = "/$name.apk", origin: ExtensionOrigin = ExtensionOrigin.PACKAGE) = ExtensionPackage(
        packageName = name, name = name, versionName = "1.0", versionCode = 1, signer = signer, apk = File(apk), origin = origin,
        kind = MihonKind, sourceClasses = listOf(".Source"),
    )
}

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

internal class StubAnimeSource(override val id: Long, override val lang: String) : AnimeHttpSource() {
    override val name = "Stub"
    override val baseUrl = "https://anime.example"
    override val supportsLatest = false
}

internal class StubHttpSource(override val id: Long, override val lang: String) : HttpSource() {
    override val name = "Stub"
    override val baseUrl = "https://stub.test"
    override val supportsLatest = false
    override val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(answering { cannedResponse(it, body = "source") }).build()

    override fun headersBuilder() = Headers.Builder()
}

internal class ConfigurableStub(private val setup: (PreferenceScreen) -> Unit) : StubSource(7, "en"), ConfigurableSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) = setup(screen)
}
