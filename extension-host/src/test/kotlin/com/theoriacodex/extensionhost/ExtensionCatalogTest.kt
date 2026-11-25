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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCatalogTest {
    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val repoUrl = "https://repo.example/anime"

    private val site = mutableMapOf<String, ByteArray>()

    private val store = object : ExtensionChoiceStore {
        var choices = ExtensionChoices()

        override suspend fun choices() = choices

        override suspend fun update(transform: (ExtensionChoices) -> ExtensionChoices) {
            choices = transform(choices)
        }
    }

    private val folder = Files.createTempDirectory("installed").toFile()

    private val system = mutableListOf<ExtensionPackage>()
    private var changes = 0

    private val catalog = catalog()

    private fun catalog(
        client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val body = site[url]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("")
                .body(Buffer().write(body ?: ByteArray(0)).asResponseBody("application/octet-stream".toMediaType(), contentLength = -1)).build()
        }.build(),
    ) = ExtensionCatalog(
        choices = store,
        client = client,
        kinds = listOf(MihonKind, AniyomiKind),
        packages = {
            val private = folder.listFiles { file -> file.extension == "apk" }.orEmpty().mapNotNull { read(it, ExtensionOrigin.PRIVATE) }
            private + system.filter { installed -> private.none { it.packageName == installed.packageName } }
        },
        read = { read(it) },
        folder = folder,
        onChanged = { changes++ },
    )

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
    fun `a repo is added with the key it names, once per address, and refused when a backup knew another key (ADR 0007, 0063)`() = runTest {
        serve()
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("$repoUrl/index.min.json"))))
        assertEquals(listOf(ExtensionRepo(repoUrl, "Anime", key)), catalog.repos())

        site["https://other.example/manga/repo.json"] = site.getValue("$repoUrl/repo.json")
        site["https://other.example/manga/index.min.json"] = "[]".encodeToByteArray()
        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo("$repoUrl/"), NewExtensionRepo("https://other.example/manga/index.min.json"))))
        assertEquals(listOf(repoUrl, "https://other.example/manga"), catalog.repos().map { it.url })

        catalog.remove(repoUrl)
        catalog.remove("https://other.example/manga")
        site["https://nokey.example/anime/repo.json"] = """{"meta":{"name":"Keyless","website":"w","signingKeyFingerprint":"abc"}}""".encodeToByteArray()
        site["https://nokey.example/anime/index.min.json"] = "[]".encodeToByteArray()
        val refused = catalog.add(
            listOf(NewExtensionRepo(repoUrl, signingKey = otherKey), NewExtensionRepo("http://repo.example/anime"), NewExtensionRepo("https://gone.example"), NewExtensionRepo("https://nokey.example/anime")),
        )
        assertEquals(setOf(repoUrl, "http://repo.example/anime", "https://gone.example", "https://nokey.example/anime"), refused.keys)
        assertEquals("Now signed with another key", refused[repoUrl])
        assertEquals("Names no signing key", refused["https://nokey.example/anime"])
        assertEquals("Answered 404", refused["https://gone.example"])
        assertEquals(emptyList<ExtensionRepo>(), catalog.repos())
    }

    @Test
    fun `adding, restoring or removing a repo and re-reading the indexes each signal that the listing changed (ADR 0221)`() = runTest {
        serve()
        val signals = mutableListOf<Unit>()
        val watching = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.changes.collect { signals += it } }

        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        catalog.available(refresh = false)
        assertEquals(1, signals.size)
        catalog.available(refresh = true)
        catalog.restore(emptyList())
        catalog.remove(repoUrl)
        assertEquals(4, signals.size)
        watching.cancel()
    }

    @Test
    fun `a refresh that overlaps a slower first read of the same repo keeps the refreshed index (ADR 0221)`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val slowFirst = catalog(
            OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = site[url]
                if (url.endsWith("index.min.json") && held.compareAndSet(false, true)) {
                    reached.complete(Unit)
                    release.await()
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("")
                    .body(Buffer().write(body ?: ByteArray(0)).asResponseBody("application/octet-stream".toMediaType(), contentLength = -1)).build()
            }.build(),
        )
        serve(Triple("old", 1, "16"))
        slowFirst.restore(listOf(ExtensionRepo(repoUrl, "Anime", key)))

        val first = async(Dispatchers.IO) { slowFirst.available(refresh = false) }
        reached.await()
        serve(Triple("old", 2, "16"))
        val refreshed = async(Dispatchers.IO) { slowFirst.available(refresh = true) }
        withTimeoutOrNull(OVERLAP_MS) { refreshed.await() }
        release.countDown()
        first.await()
        refreshed.await()

        assertEquals(listOf("16.2"), slowFirst.available().extensions.map { it.versionName })
    }

    @Test
    fun `restoring repos stores the valid records in one update without reading them, skips present ones and refuses a bad address or key (ADR 0007)`() = runTest {
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
    fun `each extension the host runs is listed with how the device stands with it (ADR 0063)`() = runTest {
        serve(Triple("new", 2, "16"), Triple("current", 5, "14"), Triple("old", 7, "16"), Triple("foreign", 1, "16"), Triple("unsupported", 1, "15"))
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        system += listOf(extension("current", key, 5), extension("old", key, 6), extension("foreign", otherKey, 1))

        val listing = catalog.available()

        assertEquals(
            mapOf("new" to Status.AVAILABLE, "current" to Status.INSTALLED, "old" to Status.UPDATE, "foreign" to Status.OTHER_KEY),
            listing.extensions.associate { it.packageName to it.status },
        )
        assertEquals(emptyMap<String, String>(), listing.problems)

        site["https://mirror.example/anime/repo.json"] = site.getValue("$repoUrl/repo.json")
        site["https://mirror.example/anime/index.min.json"] = site.getValue("$repoUrl/index.min.json")
        catalog.add(listOf(NewExtensionRepo("https://mirror.example/anime")))
        assertEquals(listing.extensions, catalog.available().extensions)
        catalog.remove("https://mirror.example/anime")

        site["$repoUrl/repo.json"] = site.getValue("$repoUrl/repo.json").decodeToString().replace(key, otherKey).encodeToByteArray()
        val changed = catalog.available(refresh = true)
        assertEquals(emptyList<Any>(), changed.extensions)
        assertTrue(changed.problems.getValue(repoUrl).startsWith("Now signed with another key"))
        val restarted = catalog().available()
        assertEquals(emptyList<Any>(), restarted.extensions)
        assertTrue(restarted.problems.getValue(repoUrl).startsWith("Now signed with another key"))
    }

    @Test
    fun `torrent extensions and ones of an interface the host does not run are not offered (ADR 0063)`() = runTest {
        serve(Triple("kept", 1, "16"), Triple("torrent", 1, "16"), Triple("unsupported", 1, "15"))
        site["$repoUrl/index.min.json"] = site.getValue("$repoUrl/index.min.json").decodeToString()
            .replace("\"pkg\":\"torrent\"", "\"pkg\":\"torrent\",\"torrent\":1")
            .replace("]", """,{"name":"Tachiyomi: Manga at 16","pkg":"manga16","apk":"m.apk","lang":"en","code":1,"version":"16.1"},""" +
                """{"name":"Aniyomi: Anime at 1.6","pkg":"anime16","apk":"a.apk","lang":"en","code":1,"version":"1.6.4"}]""")
            .encodeToByteArray()
        catalog.add(listOf(NewExtensionRepo(repoUrl)))

        assertEquals(listOf("kept"), catalog.available().extensions.map { it.packageName })
    }

    @Test
    fun `a repo whose index is over 8 MiB, as declared, as sent or once gunzipped, is refused without reading it all (ADR 0063)`() = runTest {
        val limit = 8 * 1024 * 1024
        var body = ByteArray(0)
        var declared = -1L
        var read = 0L
        val sizing = catalog(
            OkHttpClient.Builder().addInterceptor { chain ->
                read = 0
                val counted = object : ForwardingSource(Buffer().write(body)) {
                    override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) read += it }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body(counted.buffer().asResponseBody("application/json".toMediaType(), declared)).build()
            }.build(),
        )
        suspend fun problem(sent: ByteArray, declaredLength: Long = -1): String? {
            body = sent
            declared = declaredLength
            return sizing.add(listOf(NewExtensionRepo("https://huge.example")))["https://huge.example"]
        }
        fun gzipped(size: Int) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(size) { ' '.code.toByte() }) } }.toByteArray()

        assertTrue(problem(ByteArray(limit) { ' '.code.toByte() }) != "Too large")
        assertEquals("Too large", problem(ByteArray(limit + 1) { ' '.code.toByte() }))
        assertEquals("Too large", problem(ByteArray(3 * limit) { ' '.code.toByte() }))
        assertTrue(read < 2L * limit)
        assertEquals("Too large", problem("{}".encodeToByteArray(), declaredLength = limit + 1L))
        assertEquals(0L, read)
        assertTrue(problem(gzipped(limit)) != "Too large")
        assertEquals("Too large", problem(gzipped(limit + 1)))
    }

    @Test
    fun `an extension download declared over its size limit is refused before any of it is read (ADR 0063)`() = runTest {
        serve(Triple("big", 2, "16"))
        var declared = 0L
        var read = 0L
        val sized = catalog(
            OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url.toString()
                val apk = url == "$repoUrl/apk/big.apk"
                val body = if (apk) "big|$key|2".encodeToByteArray() else site[url]
                val counted = object : ForwardingSource(Buffer().write(body ?: ByteArray(0))) {
                    override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (apk && it > 0) read += it }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (body == null) 404 else 200).message("")
                    .body(counted.buffer().asResponseBody("application/octet-stream".toMediaType(), if (apk) declared else -1)).build()
            }.build(),
        )
        sized.add(listOf(NewExtensionRepo(repoUrl)))

        declared = 64L * 1024 * 1024 + 1
        assertEquals("Too large", sized.install(repoUrl, "big"))
        assertEquals(0L, read)
        assertEquals(emptyList<String>(), folder.list().orEmpty().toList())
        declared = "big|$key|2".length.toLong()
        assertNull(sized.install(repoUrl, "big"))
    }

    @Test
    fun `an extension installed from a repo is kept privately and turned on, an update keeps the user's choice, and uninstalling turns it off (ADR 0007, 0063)`() = runTest {
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
        assertEquals(3, changes)
    }

    @Test
    fun `adding a repo installs and turns on nothing (ADR 0007)`() = runTest {
        serve(Triple("new", 2, "16"))
        apk("new", key, 2)

        assertEquals(emptyMap<String, String>(), catalog.add(listOf(NewExtensionRepo(repoUrl))))

        assertEquals(emptyList<String>(), folder.list().orEmpty().toList())
        assertEquals(emptyMap<String, String>(), store.choices.trusted)
        assertEquals(0, changes)
        assertEquals(listOf(Status.AVAILABLE), catalog.available().extensions.map { it.status })
    }

    @Test
    fun `nothing is installed that is not the package the repo named, signed with its key, and no older than the copy on the device (ADR 0007, 0063)`() = runTest {
        serve(Triple("swapped", 1, "16"), Triple("unsigned", 1, "16"), Triple("older", 3, "16"), Triple("foreign", 2, "16"))
        catalog.add(listOf(NewExtensionRepo(repoUrl)))
        apk("swapped", key, 1)
        site["$repoUrl/apk/swapped.apk"] = "other|$key|1".encodeToByteArray()
        apk("unsigned", otherKey, 1)
        apk("older", key, 3)
        apk("foreign", key, 2)
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

private const val OVERLAP_MS = 500L
