package com.theoriacodex.mihonhost

import androidx.preference.PreferenceScreen
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionLanguage
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.io.File
import java.util.concurrent.CountDownLatch
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

        override suspend fun choices() = choices

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
            choices = transform(choices)
        }
    }
    private val registry = ExtensionRegistry(
        packages = { onDevice },
        load = { loads += it.apk.path; sourcesOf(it) },
        choices = store,
        images = ExtensionImages(),
        settings = { error("No source here has settings") },
        deviceLanguages = { deviceLanguages },
        limitMs = 500,
    )

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
        registry.setOn("/a.apk", true)

        assertEquals(setOf("key-1"), store.choices.trustedSigners)
        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        assertTrue(registry.installed().single().on)
        assertEquals(listOf("/a.apk"), loads)
        assertEquals(1, registry.revision.value)

        registry.setOn("/a.apk", false)

        assertTrue(registry.sources().isEmpty())
        assertFalse(registry.installed().single().on)
    }

    @Test
    fun `an update signed with the same key stays on and a copy signed with another key is off`() = runBlocking {
        registry.setOn("/a.apk", true)

        onDevice = listOf(extension("a", signer = "key-1", apk = "/a-2.apk"))
        assertTrue(registry.installed().single().on)
        assertEquals(listOf("/a.apk", "/a-2.apk"), loads)

        onDevice = listOf(extension("a", signer = "key-2", apk = "/a-3.apk"))
        assertFalse(registry.installed().single().on)
        assertTrue(registry.sources().isEmpty())
        assertEquals(listOf("/a.apk", "/a-2.apk"), loads)
    }

    @Test
    fun `an extension without a signing certificate cannot be turned on`() = runBlocking {
        onDevice = listOf(extension("a", signer = null))
        // Even a store that somehow holds an empty fingerprint trusts nothing.
        store.choices = ExtensionChoices(trustedSigners = setOf(""))

        registry.setOn("/a.apk", true)
        val listed = registry.installed().single()

        assertFalse(listed.on)
        assertEquals("Not signed", listed.problem)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `only the device's language is on until the user chooses`() = runBlocking {
        sourcesOf = { listOf(StubSource(1, "en"), StubSource(2, "es"), StubSource(3, "ko")) }
        deviceLanguages = listOf("es-PR")

        registry.setOn("/a.apk", true)

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

        registry.setOn("/works.apk", true)
        val listed = registry.installed().associate { it.name to it.problem }

        assertEquals(mapOf("hangs" to "Took longer than 0 s", "fails" to "androidx/missing/Thing", "works" to null), listed)
        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        never.countDown()
    }

    @Test
    fun `the same extension on the device twice offers its sources once`() = runBlocking {
        onDevice = listOf(extension("a", signer = "key-1"), extension("a", signer = "key-1", apk = "/files/a.apk"))

        registry.setOn("/a.apk", true)

        assertEquals(listOf("custom_mihon_1"), registry.sources().map { it.key.id })
        assertNull(registry.installed().last().problem)
    }

    private fun extension(name: String, signer: String?, apk: String = "/$name.apk") =
        ExtensionPackage(packageName = name, name = name, versionName = "1.0", signer = signer, apk = File(apk), installed = true, sourceClasses = listOf(".Source"))
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

/** A source whose settings screen is [setup]. */
internal class ConfigurableStub(private val setup: (PreferenceScreen) -> Unit) : StubSource(7, "en"), ConfigurableSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) = setup(screen)
}
