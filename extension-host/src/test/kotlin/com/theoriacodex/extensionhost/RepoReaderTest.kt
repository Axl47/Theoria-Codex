package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.SeriesKind
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RepoReaderTest {
    private val signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    /**
     * Newer repos' indexes, a manga store and an anime one, built from `repos/store.txt` and `repos/anime.txt` with
     * `protoc --encode=Store store.proto < store.txt | gzip -9` (and `--encode=AnimeStore` for the anime one).
     */
    private val store = resource("store.pb.gz")
    private val animeStore = resource("anime.pb.gz")

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/repos/$name")).use { it.readBytes() }

    private val legacyRepo = """{"meta":{"name":"Yūzōnō","shortName":"Yū","website":"https://yuzono.github.io","signingKeyFingerprint":"$signingKey"}}"""
    private val legacyIndex = """[
        {"name":"Aniyomi: AnimeOnsen","pkg":"eu.kanade.tachiyomi.animeextension.all.animeonsen","apk":"aniyomi-all.animeonsen-v16.12.apk",
         "lang":"all","code":12,"version":"16.12","nsfw":0,"torrent":0,"sources":[{"name":"AnimeOnsen","lang":"all","id":"85","baseUrl":"https://a"}]},
        {"name":"Aniyomi: Torrents","pkg":"eu.kanade.tachiyomi.animeextension.en.torrents","apk":"t.apk","lang":"en","code":3,"version":"14.3","nsfw":1,"torrent":1}
    ]"""

    @Test
    fun `a legacy repo is read from its folder, its index address or its repo address alike`() = runTest {
        val site = mapOf(
            "https://repo.example/anime/repo.json" to legacyRepo,
            "https://repo.example/anime/index.min.json" to legacyIndex,
        ).mapValues { it.value.encodeToByteArray() }
        for (address in listOf("https://repo.example/anime", "https://repo.example/anime/", "https://repo.example/anime/index.min.json", "https://repo.example/anime/repo.json")) {
            val index = RepoReader.read(RepoReader.storedAddress(address)) { url -> site[url] ?: throw IOException("404 $url") }
            assertEquals("Yūzōnō", index.name)
            assertEquals(signingKey, index.signingKey)
            val onsen = index.extensions.first()
            assertEquals("AnimeOnsen", onsen.name)
            assertEquals("https://repo.example/anime/apk/aniyomi-all.animeonsen-v16.12.apk", onsen.apkUrl)
            assertEquals("16", onsen.libVersion)
            assertEquals(SeriesKind.ANIME, onsen.kind)
            assertEquals(listOf(false, true), index.extensions.map { it.torrent })
            assertEquals(listOf("en"), index.extensions[1].languages)
        }
        assertEquals("https://repo.example/anime", RepoReader.storedAddress(" https://repo.example/anime/index.min.json "))
    }

    @Test
    fun `a legacy repo naming a newer index is read from that index, as Keiyoushi's is`() = runTest {
        val stub = """{"index_v2":"https://repo.example/store/index.pb","meta":{"name":"Old","website":"w","signingKeyFingerprint":"$signingKey"}}"""
        val site = mapOf(
            "https://repo.example/store/repo.json" to stub.encodeToByteArray(),
            // What old apps read: a stub telling them to update.
            "https://repo.example/store/index.min.json" to """[{"name":"Outdated App","pkg":"x","apk":"x.apk","code":1,"version":"1.4.1"}]""".encodeToByteArray(),
            "https://repo.example/store/index.pb" to store,
        )

        val index = RepoReader.read("https://repo.example/store") { url -> site[url] ?: throw IOException("404 $url") }

        assertEquals("Fixture", index.name)
        assertEquals("The key is compared as lowercase hex", signingKey, index.signingKey)
        assertEquals(
            listOf(IndexedExtension("Achy", "eu.kanade.tachiyomi.extension.all.achy", "https://cdn.example.org/achy-v1.6.1.apk", "1.6", 106001, "1.6.1", listOf("es", "en"), SeriesKind.MANGA)),
            index.extensions,
        )
    }

    @Test
    fun `an anime store is read from its own address, with its torrent flag where a manga store has sources`() = runTest {
        val index = RepoReader.read("https://repo.example/anime/index.pb") { url -> if (url.endsWith("index.pb")) animeStore else throw IOException("404 $url") }
        assertEquals("Anime fixture", index.name)
        assertEquals(
            listOf(
                IndexedExtension("Onsen", "eu.kanade.tachiyomi.animeextension.all.onsen", "https://cdn.example.org/onsen-v16.12.apk", "16", 12, "16.12", listOf("all"), SeriesKind.ANIME),
                IndexedExtension("Torrents", "eu.kanade.tachiyomi.animeextension.all.torrents", "https://cdn.example.org/t-v14.6.apk", "14", 6, "14.6", listOf("all"), SeriesKind.ANIME, torrent = true),
            ),
            index.extensions,
        )
    }

    @Test
    fun `the kinds run the interface versions repos state`() {
        assertEquals(listOf(true, false), listOf("1.6", "1.4").map(MihonKind::runs))
        assertEquals(listOf(true, true, false), listOf("14", "16", "15").map(AniyomiKind::runs))
    }
}
