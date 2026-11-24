package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension.Status
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCatalogTest {
    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val repoUrl = "https://repo.example/anime"

    /** What the repo serves, by address; anything else answers 404. */
    private val site = mutableMapOf<String, ByteArray>()

    private val store = object : ExtensionChoiceStore {
        var choices = ExtensionChoices()

        override suspend fun choices() = choices

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
            choices = transform(choices)
        }
    }

    private val folder = Files.createTempDirectory("installed").toFile()

    /** Packages Android installed, which a private copy of the same name stands in for, as [ExtensionPackages] lists them. */
    private val system = mutableListOf<ExtensionPackage>()
    private var changes = 0

    private val catalog = ExtensionCatalog(
        choices = store,
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val body = site[url]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("")
                .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
        }.build(),
        kinds = listOf(MihonKind, AniyomiKind),
        packages = {
            val private = folder.listFiles { file -> file.extension == "apk" }.orEmpty().mapNotNull { read(it, ExtensionOrigin.PRIVATE) }
            private + system.filter { installed -> private.none { it.packageName == installed.packageName } }
        },
        read = { read(it) },
        folder = folder,
        onChanged = { changes++ },
    )

    /** A fake APK is its own description: `package|signer|versionCode`, read back as [ExtensionPackages.read] would. */
    private fun read(file: File, origin: ExtensionOrigin = ExtensionOrigin.FILE): ExtensionPackage? {
        val (name, signer, version) = file.readText().split('|').takeIf { it.size == 3 } ?: return null
        return extension(name, signer, version.toLong(), file, origin)
    }

    private fun extension(name: String, signer: String, version: Long, apk: File = File("/data/app/$name/base.apk"), origin: ExtensionOrigin = ExtensionOrigin.PACKAGE) =
        ExtensionPackage(name, name, "16.$version", version, signer, apk, origin, AniyomiKind, listOf(".Source"), size = 1, modified = 1)

    private fun serve(vararg entries: Triple<String, Long, String>) {
        site["$repoUrl/repo.json"] = """{"meta":{"name":"Anime","website":"w","signingKeyFingerprint":"$key"}}""".encodeToByteArray()
        site["$repoUrl/index.min.json"] = entries.joinToString(",", "[", "]") { (name, version, lib) ->
            """{"name":"Aniyomi: $name","pkg":"$name","apk":"$name.apk","lang":"en","code":$version,"version":"$lib.$version","nsfw":0}"""
        }.encodeToByteArray()
    }

    private fun apk(name: String, signer: String, version: Long) {
        site["$repoUrl/apk/$name.apk"] = "$name|$signer|$version".encodeToByteArray()
    }

    @Test
    fun `a repo is added with the key it names, once whatever address reaches it, and refused when a backup knew another key`() = runTest {
        serve()
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("$repoUrl/index.min.json"))))
        assertEquals(listOf(ExtensionRepo(repoUrl, "Anime", key)), catalog.repos())

        // Another address of the same repo, and the same one again, as a backup restored twice would give.
        site["https://mirror.example/anime/repo.json"] = site.getValue("$repoUrl/repo.json")
        site["https://mirror.example/anime/index.min.json"] = site.getValue("$repoUrl/index.min.json")
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("https://mirror.example/anime"), NewExtensionRepo(repoUrl))))
        assertEquals(1, catalog.repos().size)

        catalog.remove(repoUrl)
        val refused = catalog.add(listOf(NewExtensionRepo(repoUrl, signingKey = otherKey), NewExtensionRepo("http://repo.example/anime"), NewExtensionRepo("https://gone.example")))
        assertEquals(setOf(repoUrl, "http://repo.example/anime", "https://gone.example"), refused.keys)
        assertEquals("Now signed with another key", refused[repoUrl])
        assertEquals("Answered 404", refused["https://gone.example"])
        assertEquals(emptyList<ExtensionRepo>(), catalog.repos())
    }

    @Test
    fun `each extension the host runs is listed with how the device stands with it`() = runTest {
        serve(Triple("new", 2, "16"), Triple("current", 5, "14"), Triple("old", 7, "16"), Triple("foreign", 1, "16"), Triple("unsupported", 1, "15"))
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        system += listOf(extension("current", key, 5), extension("old", key, 6), extension("foreign", otherKey, 1))

        val listing = catalog.available()

        assertEquals(
            mapOf("new" to Status.AVAILABLE, "current" to Status.INSTALLED, "old" to Status.UPDATE, "foreign" to Status.OTHER_KEY),
            listing.extensions.associate { it.packageName to it.status },
        )
        assertEquals(emptyMap<String, String>(), listing.problems)

        // The repo turns to another key: nothing it offers is listed until the user adds it again.
        site["$repoUrl/repo.json"] = site.getValue("$repoUrl/repo.json").decodeToString().replace(key, otherKey).encodeToByteArray()
        val changed = catalog.available(refresh = true)
        assertEquals(emptyList<Any>(), changed.extensions)
        assertTrue(changed.problems.getValue(repoUrl).startsWith("Now signed with another key"))
    }

    @Test
    fun `an extension installed from a repo is kept privately and turned on, and an update keeps the user's choice`() = runTest {
        serve(Triple("new", 2, "16"), Triple("old", 7, "16"))
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        apk("new", key, 2)
        apk("old", key, 7)
        system += extension("old", key, 6)

        assertNull(catalog.install(repoUrl, "new"))
        assertNull(catalog.install(repoUrl, "old"))

        assertEquals(setOf("new.apk", "old.apk"), folder.list().orEmpty().toSet())
        assertEquals("Only the extension that was not on the device is turned on", mapOf("new" to key), store.choices.trusted)
        assertEquals(2, changes)
        assertEquals(setOf(Status.INSTALLED), catalog.available().extensions.map { it.status }.toSet())

        catalog.uninstall(File(folder, "new.apk").path)
        assertFalse(File(folder, "new.apk").exists())
        assertEquals(emptyMap<String, String>(), store.choices.trusted)
    }

    @Test
    fun `nothing is installed that is not the package the repo named, signed with its key, and no older than the copy on the device`() = runTest {
        serve(Triple("swapped", 1, "16"), Triple("unsigned", 1, "16"), Triple("older", 3, "16"), Triple("foreign", 2, "16"))
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        apk("swapped", key, 1)
        site["$repoUrl/apk/swapped.apk"] = "other|$key|1".encodeToByteArray()
        apk("unsigned", otherKey, 1)
        apk("older", key, 3)
        apk("foreign", key, 2)
        // The index can lag behind an APK already installed from it.
        system += listOf(extension("older", key, 4), extension("foreign", otherKey, 1))

        assertEquals("The download is not swapped", catalog.install(repoUrl, "swapped"))
        assertEquals("Not signed with the repo's key", catalog.install(repoUrl, "unsigned"))
        assertEquals("Older than the copy on this device", catalog.install(repoUrl, "older"))
        assertEquals("The copy on this device is signed with another key", catalog.install(repoUrl, "foreign"))
        assertEquals("No longer in the repo", catalog.install(repoUrl, "missing"))

        assertEquals(emptyList<String>(), folder.list().orEmpty().toList())
        assertEquals(emptyMap<String, String>(), store.choices.trusted)
        assertEquals(0, changes)
    }
}
