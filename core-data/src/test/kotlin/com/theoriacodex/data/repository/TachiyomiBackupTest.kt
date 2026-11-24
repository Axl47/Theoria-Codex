package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionRepo
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.NewExtensionRepo
import com.theoriacodex.domain.series.RepoListing
import com.theoriacodex.domain.series.SeriesStatus
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Backups are written field by field here, so the field numbers are checked against Mihon's and Anikku's, not ours. */
class TachiyomiBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `manga keep their source, follow and progress, in Theoria's units`() {
        val series = decodeTachiyomiBackup(backup {
            message(1) {
                long(1, 42); string(2, "/series/a"); string(3, "A"); string(5, "Author"); string(7, "Action"); long(8, 1); long(13, 300)
                message(16) { string(1, "/a/2"); string(2, "Chapter 2"); long(6, 4); long(7, 500); float(9, 2f); long(10, 0) }
                message(16) { string(1, "/a/1"); string(2, "Chapter 1"); bool(4, true); long(8, 100); float(9, 1f); long(10, 1) }
                message(104) { string(1, "/a/2"); long(2, 900) }
                message(104) { string(1, "/a/1"); long(2, 800) }
            }
        }, now = 7).series

        val a = series.single()
        assertEquals("custom_mihon_42", a.series.series.id.source.id)
        assertEquals("/series/a", a.series.series.id.address)
        assertTrue("Mihon writes no favorite flag for a series in the library", a.series.followed)
        assertEquals(SeriesStatus.ONGOING, a.series.series.status)
        assertEquals("/a/2", a.series.lastOpenedEntry)
        assertEquals(300L, a.series.followedAtEpochMs)
        assertEquals(listOf(900L, 800L), a.entries.map { it.lastReadAtEpochMs })
        val (second, first) = a.entries
        assertEquals(5L, second.position) // Mihon's page index 4 is page 5.
        assertFalse(second.read)
        assertEquals(500L, second.firstSeenAtEpochMs)
        assertTrue(first.read)
        assertEquals(0L, first.position)
        assertEquals(7L, first.firstSeenAtEpochMs)
        assertEquals(100L, first.entry.uploadedAtEpochMs)
        assertEquals(1, first.sourceOrder)
    }

    @Test
    fun `extension memos come along, and an empty or broken one is none`() {
        val series = decodeTachiyomiBackup(backup {
            message(1) {
                long(1, 1); string(2, "/m"); string(3, "M"); string(112, """{"slug":"m-1"}""")
                message(16) { string(1, "/m/3"); string(13, """{"id":3}"""); long(10, 0) }
                message(16) { string(1, "/m/2"); string(13, "{}"); long(10, 1) }
                message(16) { string(1, "/m/1"); string(13, "not json"); long(10, 2) }
            }
            message(1) { long(1, 1); string(2, "/n"); string(3, "N"); string(112, "{}") }
        }, now = 0).series

        assertEquals(listOf("""{"slug":"m-1"}""", null), series.map { it.series.series.memo })
        assertEquals(listOf("""{"id":3}""", null, null), series.first().entries.map { it.entry.memo })
    }

    @Test
    fun `TachiyomiJ2K's pages left marks a chapter opened on its first page and gives its page count`() {
        val entries = decodeTachiyomiBackup(backup {
            message(1) {
                long(1, 1); string(2, "/j"); string(3, "J")
                message(16) { string(1, "/j/3"); long(800, 20); long(10, 0) }
                message(16) { string(1, "/j/2"); long(6, 4); long(800, 6); long(10, 1) }
                message(16) { string(1, "/j/1"); bool(4, true); long(6, 9); long(800, 1); float(9, Float.POSITIVE_INFINITY); long(10, 2) }
            }
        }, now = 0).series.single().entries

        assertEquals(listOf(1L, 5L, 10L), entries.map { it.position })
        assertEquals(listOf(20L, 10L, 10L), entries.map { it.length })
        assertNull("A number no chapter has is none", entries.last().entry.number)
    }

    @Test
    fun `anime come from Anikku's field with their watched position and duration`() {
        val series = decodeTachiyomiBackup(backup {
            message(501) {
                long(1, 9); string(2, "/watch/x"); string(3, "X")
                message(16) { string(1, "ep1"); string(2, "Episode 1"); long(6, 211_000); long(16, 1_420_000) }
            }
        }, now = 0).series

        val x = series.single()
        assertEquals("custom_aniyomi_9", x.series.series.id.source.id)
        assertEquals(211_000L, x.entries.single().position)
        assertEquals(1_420_000L, x.entries.single().length)
    }

    @Test
    fun `Aniyomi's anime come from its own field`() {
        val series = decodeTachiyomiBackup(backup { message(3) { long(1, 9); string(2, "/watch/y"); string(3, "Y") } }, now = 0).series

        assertEquals("custom_aniyomi_9", series.single().series.series.id.source.id)
    }

    @Test
    fun `series neither followed nor started, and the local source's, are left behind`() {
        val series = decodeTachiyomiBackup(backup {
            message(1) { long(1, 1); string(2, "/untouched"); string(3, "U"); bool(100, false); message(16) { string(1, "/u/1") } }
            message(1) { long(1, 1); string(2, "/started"); string(3, "S"); bool(100, false); message(16) { string(1, "/s/1"); bool(4, true) } }
            message(1) { long(1, 0); string(2, "local.cbz"); string(3, "L") }
        }, now = 0).series

        val started = series.single()
        assertEquals("/started", started.series.series.id.address)
        assertFalse(started.series.followed)
    }

    @Test
    fun `an unfollowed series with progress but no history continues from its furthest entry`() {
        val series = decodeTachiyomiBackup(backup {
            message(1) {
                long(1, 1); string(2, "/s"); string(3, "S"); bool(100, false)
                message(16) { string(1, "/s/2"); long(10, 0) }
                message(16) { string(1, "/s/1"); bool(4, true); long(10, 1) }
            }
        }, now = 0).series

        assertEquals("/s/1", series.single().series.lastOpenedEntry)
    }

    @Test
    fun `the backup service previews and imports a Mihon-family backup as series only`() = runTest {
        val bytes = backup {
            message(1) { long(1, 1); string(2, "/m"); string(3, "M"); message(16) { string(1, "/m/1"); bool(4, true) } }
            message(501) { long(1, 1); string(2, "/a"); string(3, "A"); message(16) { string(1, "/a/1") } }
        }
        val store = RecordingStore()
        val directory = temporary.newFolder()
        val service = ProfileBackupService(InMemorySettingsRepository(), store, InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"))
        val expected = SeriesImportPreview(manga = 1, anime = 1, chaptersRead = 1, episodesWatched = 0)

        // No extension is on, and the backup names neither source.
        assertEquals(BackupPreview.Series(expected, unnamedMissingSources = 2), service.preview(bytes))
        assertEquals(SeriesImportResult(expected), service.importSeries(bytes))
        val merged = store.merged.single()
        assertEquals(2, merged.series.size)
        assertTrue(merged.codices.isEmpty() && merged.posts.isEmpty() && merged.likes.isEmpty())

        directory.resolve("restore.json").writeText("{}")
        assertThrows(IllegalStateException::class.java) { runBlocking { service.importSeries(bytes) } }
        assertEquals(1, store.merged.size)
    }

    @Test
    fun `importing adds the repos Mihon and Anikku list and the preview names the sources still missing`() = runTest {
        val bytes = backup {
            message(1) { long(1, 1); string(2, "/m"); string(3, "M") }
            message(1) { long(1, 2); string(2, "/n"); string(3, "N") }
            message(1) { long(1, 2); string(2, "/o"); string(3, "O") }
            message(101) { string(1, "MangaDex"); long(2, 1) }
            message(101) { string(1, "Comick"); long(2, 2) }
            message(106) { string(1, "https://manga.example/repo"); string(2, "Manga"); string(4, "https://manga.example"); string(5, "aa11") }
            message(501) { long(1, 1); string(2, "/a"); string(3, "A") }
            message(501) { long(1, 3); string(2, "/b"); string(3, "B") }
            message(503) { string(1, "Gogoanime"); long(2, 1) }
            message(505) { string(1, "https://anime.example/repo"); string(2, "Anime"); string(5, "bb22") }
            message(505) { string(1, "https://manga.example/repo/"); string(5, "aa11") }
            message(505) { string(1, "https://more.example/repo"); string(5, "cc33") }
            // Neither comes back: one names no key, and older Aniyomi backups keep an installed extension here.
            message(505) { string(1, "https://unsigned.example/repo") }
            message(106) { string(1, "eu.kanade.tachiyomi.animeextension.en.example"); string(2, "apk bytes") }
        }
        val repos = RecordingRepos(refuse = "https://anime.example/repo", added = "https://manga.example/repo")
        val directory = temporary.newFolder()
        // MangaDex is on here; the anime source numbered 3 is missing but the backup gives it no name.
        val service = ProfileBackupService(InMemorySettingsRepository(), RecordingStore(), InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"),
            extensionRepos = repos, extensionSources = { setOf(SourceKey.parseOrThrow("custom_mihon_1")) })

        val preview = service.preview(bytes) as BackupPreview.Series
        assertEquals("The manga repo is added already", listOf("Anime", "https://more.example/repo"), preview.newRepos)
        assertEquals(listOf("Comick", "Gogoanime"), preview.missingSources)
        assertEquals(1, preview.unnamedMissingSources)

        val result = service.importSeries(bytes)
        val given = listOf(
            NewExtensionRepo("https://manga.example/repo", "aa11"),
            NewExtensionRepo("https://anime.example/repo", "bb22"),
            NewExtensionRepo("https://more.example/repo", "cc33"),
        )
        assertEquals(given, repos.given)
        assertEquals(1, result.reposAdded)
        assertEquals(mapOf("https://anime.example/repo" to "Now signed with another key"), result.repoProblems)
    }

    @Test
    fun `repos that cannot be read leave the series import standing`() = runTest {
        val bytes = backup {
            message(1) { long(1, 1); string(2, "/m"); string(3, "M") }
            message(106) { string(1, "https://manga.example/repo"); string(5, "aa11") }
        }
        val failing = object : ExtensionRepos by RecordingRepos(refuse = "", added = "") {
            override suspend fun repos(): List<ExtensionRepo> = throw IOException("choices unreadable")
        }
        val store = RecordingStore()
        val directory = temporary.newFolder()
        val service = ProfileBackupService(InMemorySettingsRepository(), store, InMemorySavedSearchRepository(),
            FileBackedReadingPositionRepository(directory), directory.resolve("restore.json"),
            extensionRepos = failing, extensionSources = { throw IOException("extensions unreadable") })
        val counts = SeriesImportPreview(manga = 1, anime = 0, chaptersRead = 0, episodesWatched = 0)

        assertEquals(BackupPreview.Series(counts, unnamedMissingSources = 1), service.preview(bytes))
        val result = service.importSeries(bytes)
        assertEquals(1, store.merged.single().series.size)
        assertEquals(SeriesImportResult(counts, repoProblems = mapOf("https://manga.example/repo" to "Import the backup again to retry")), result)
    }

    @Test
    fun `a file that is not a Mihon-family backup, or holds no series, is refused`() {
        assertThrows(IllegalArgumentException::class.java) { decodeTachiyomiBackup("{}".toByteArray(), 0) }
        assertThrows(IllegalArgumentException::class.java) { decodeTachiyomiBackup(backup { message(101) { string(1, "Source") } }, 0) }
        assertThrows(IllegalArgumentException::class.java) { decodeTachiyomiBackup(backup { message(1) { long(1, 0) } }, 0) }
    }

    @Test
    fun `a corrupt backup reaches the generic message, not the parser's`() {
        // The backup screen shows an IllegalArgumentException's message, which kotlinx's parse errors are.
        assertThrows(IllegalStateException::class.java) { decodeTachiyomiBackup(backup { long(1, 5) }, 0) }
    }
}

private class RecordingStore : ProfileBackupStore {
    val merged = mutableListOf<BackupLibrary>()
    override suspend fun snapshot(includeRecents: Boolean) = error("unused")
    override suspend fun merge(library: BackupLibrary): Int = 0.also { merged += library }
}

/** Holds [added] already and adds each other repo but [refuse], which it refuses as [ExtensionRepos.add] does a repo whose key changed. */
private class RecordingRepos(private val refuse: String, added: String) : ExtensionRepos {
    val given = mutableListOf<NewExtensionRepo>()
    private val stored = mutableListOf(ExtensionRepo(added, added, ""))
    override suspend fun repos() = stored.toList()
    override suspend fun add(repos: List<NewExtensionRepo>): Map<String, String> {
        given += repos
        val new = repos.filter { repo -> stored.none { it.url == repo.url } }
        stored += new.filter { it.url != refuse }.map { ExtensionRepo(it.url, it.url, it.signingKey.orEmpty()) }
        return new.filter { it.url == refuse }.associate { it.url to "Now signed with another key" }
    }
    override suspend fun remove(url: String) = error("unused")
    override suspend fun available(refresh: Boolean): RepoListing = error("unused")
    override suspend fun install(repo: String, packageName: String): String? = error("unused")
    override suspend fun uninstall(id: String) = error("unused")
}

private fun backup(block: Proto.() -> Unit): ByteArray {
    val gzipped = ByteArrayOutputStream()
    GZIPOutputStream(gzipped).use { it.write(Proto().apply(block).bytes()) }
    return gzipped.toByteArray()
}

/** Protobuf's wire format, as much of it as these backups use. */
private class Proto {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())

    fun long(field: Int, value: Long) { tag(field, 0); varint(value) }

    fun bool(field: Int, value: Boolean) = long(field, if (value) 1 else 0)

    fun float(field: Int, value: Float) {
        tag(field, 5)
        val bits = value.toRawBits()
        repeat(4) { out.write(bits ushr (8 * it) and 0xff) }
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray())

    fun message(field: Int, block: Proto.() -> Unit) = bytes(field, Proto().apply(block).bytes())

    private fun bytes(field: Int, value: ByteArray) { tag(field, 2); varint(value.size.toLong()); out.write(value) }
}
