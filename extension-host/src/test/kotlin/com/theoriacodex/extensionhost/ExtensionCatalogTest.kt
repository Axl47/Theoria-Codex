package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.ExtensionChoices
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoExtension.Status
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
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
    fun `a repo is added with the key it names, once per address, and refused when a backup knew another key`() = runTest {
        serve()
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("$repoUrl/index.min.json"))))
        assertEquals(listOf(ExtensionRepo(repoUrl, "Anime", key)), catalog.repos())

        // The same repo again, as a backup restored twice would give, and another repo its publisher signs with the
        // same key and name, as Yūzōnō's manga and anime repos are.
        site["https://other.example/manga/repo.json"] = site.getValue("$repoUrl/repo.json")
        site["https://other.example/manga/index.min.json"] = "[]".encodeToByteArray()
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("$repoUrl/"), NewExtensionRepo("https://other.example/manga/index.min.json"))))
        assertEquals(listOf(repoUrl, "https://other.example/manga"), catalog.repos().map { it.url })

        catalog.remove(repoUrl)
        catalog.remove("https://other.example/manga")
        val refused = catalog.add(listOf(NewExtensionRepo(repoUrl, signingKey = otherKey), NewExtensionRepo("http://repo.example/anime"), NewExtensionRepo("https://gone.example")))
        assertEquals(setOf(repoUrl, "http://repo.example/anime", "https://gone.example"), refused.keys)
        assertEquals("Now signed with another key", refused[repoUrl])
        assertEquals("Answered 404", refused["https://gone.example"])
        assertEquals(emptyList<ExtensionRepo>(), catalog.repos())
    }

    @Test
    fun `restoring repos stores the valid records in one update without reading them, skips present ones and refuses a bad address or key`() = runTest {
        var updates = 0
        val counting = object : ExtensionChoiceStore by store {
            override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
                updates++
                store.update(transform)
            }
        }
        val restoring = ExtensionCatalog(
            choices = counting,
            client = OkHttpClient.Builder().addInterceptor { error("restoring must not use the network") }.build(),
            kinds = listOf(MihonKind, AniyomiKind),
            packages = { emptyList() },
            read = { null },
            folder = folder,
        )
        store.choices = ExtensionChoices(repos = listOf(ExtensionRepo(repoUrl, "Held", key)))

        val refused = restoring.restore(listOf(
            ExtensionRepo("$repoUrl/index.min.json", "Held again", otherKey),
            ExtensionRepo("https://new.example/manga/index.min.json", "New", key.uppercase()),
            ExtensionRepo("https://unnamed.example/anime", " ", otherKey),
            ExtensionRepo("http://plain.example/anime", "Plain", key),
            ExtensionRepo("https://nokey.example/anime", "No key", "abc"),
        ))

        assertEquals(1, updates)
        assertEquals(setOf("http://plain.example/anime", "https://nokey.example/anime"), refused.keys)
        assertEquals(
            listOf(
                ExtensionRepo(repoUrl, "Held", key),
                ExtensionRepo("https://new.example/manga", "New", key),
                ExtensionRepo("https://unnamed.example/anime", "https://unnamed.example/anime", otherKey),
            ),
            restoring.repos(),
        )
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

        // A second address of the repo offers the same packages, which are listed once.
        site["https://mirror.example/anime/repo.json"] = site.getValue("$repoUrl/repo.json")
        site["https://mirror.example/anime/index.min.json"] = site.getValue("$repoUrl/index.min.json")
        catalog.add(listOf(NewExtensionRepo("https://mirror.example/anime")))
        assertEquals(listing.extensions, catalog.available().extensions)
        catalog.remove("https://mirror.example/anime")

        // The repo turns to another key: nothing it offers is listed until the user adds it again.
        site["$repoUrl/repo.json"] = site.getValue("$repoUrl/repo.json").decodeToString().replace(key, otherKey).encodeToByteArray()
        val changed = catalog.available(refresh = true)
        assertEquals(emptyList<Any>(), changed.extensions)
        assertTrue(changed.problems.getValue(repoUrl).startsWith("Now signed with another key"))
    }

    @Test
    fun `a repo whose index is too large, as fetched or once gunzipped, is refused without reading it all`() = runTest {
        site["https://huge.example/repo.json"] = ByteArray(MAX_INDEX_BYTES.toInt() + 1) { ' '.code.toByte() }
        assertEquals(mapOf("https://huge.example" to "Too large"), catalog.add(listOf(NewExtensionRepo("https://huge.example"))))

        serve()
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        // A few kilobytes that expand past the limit.
        site["$repoUrl/index.min.json"] = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(ByteArray(MAX_INDEX_BYTES.toInt() + 1)) }
        }.toByteArray()
        assertEquals(mapOf(repoUrl to "Too large"), catalog.available(refresh = true).problems)
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
        assertEquals("Not a package name", catalog.install(repoUrl, "../escape"))

        assertEquals(emptyList<String>(), folder.list().orEmpty().toList())
        assertEquals(emptyMap<String, String>(), store.choices.trusted)
        assertEquals(0, changes)
    }
}
