package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.SeriesStatus
import java.io.ByteArrayOutputStream
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
                long(1, 42); string(2, "/series/a"); string(3, "A"); string(5, "Author"); string(7, "Action"); long(8, 1)
                message(16) { string(1, "/a/2"); string(2, "Chapter 2"); long(6, 4); long(7, 500); float(9, 2f); long(10, 0) }
                message(16) { string(1, "/a/1"); string(2, "Chapter 1"); bool(4, true); long(8, 100); float(9, 1f); long(10, 1) }
                message(104) { string(1, "/a/2"); long(2, 900) }
                message(104) { string(1, "/a/1"); long(2, 800) }
            }
        }, now = 7)

        val a = series.single()
        assertEquals("custom_mihon_42", a.series.series.id.source.id)
        assertEquals("/series/a", a.series.series.id.address)
        assertTrue("Mihon writes no favorite flag for a series in the library", a.series.followed)
        assertEquals(SeriesStatus.ONGOING, a.series.series.status)
        assertEquals("/a/2", a.series.lastOpenedEntry)
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
    fun `TachiyomiJ2K's pages left marks a chapter opened on its first page and gives its page count`() {
        val entries = decodeTachiyomiBackup(backup {
            message(1) {
                long(1, 1); string(2, "/j"); string(3, "J")
                message(16) { string(1, "/j/3"); long(800, 20); long(10, 0) }
                message(16) { string(1, "/j/2"); long(6, 4); long(800, 6); long(10, 1) }
                message(16) { string(1, "/j/1"); bool(4, true); long(6, 9); long(800, 1); float(9, Float.POSITIVE_INFINITY); long(10, 2) }
            }
        }, now = 0).single().entries

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
        }, now = 0)

        val x = series.single()
        assertEquals("custom_aniyomi_9", x.series.series.id.source.id)
        assertEquals(211_000L, x.entries.single().position)
        assertEquals(1_420_000L, x.entries.single().length)
    }

    @Test
    fun `series neither followed nor started, and the local source's, are left behind`() {
        val series = decodeTachiyomiBackup(backup {
            message(1) { long(1, 1); string(2, "/untouched"); string(3, "U"); bool(100, false); message(16) { string(1, "/u/1") } }
            message(1) { long(1, 1); string(2, "/started"); string(3, "S"); bool(100, false); message(16) { string(1, "/s/1"); bool(4, true) } }
            message(1) { long(1, 0); string(2, "local.cbz"); string(3, "L") }
        }, now = 0)

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
        }, now = 0)

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

        assertEquals(BackupPreview.Series(expected), service.preview(bytes))
        assertEquals(expected, service.importSeries(bytes))
        val merged = store.merged.single()
        assertEquals(2, merged.series.size)
        assertTrue(merged.codices.isEmpty() && merged.posts.isEmpty() && merged.likes.isEmpty())

        directory.resolve("restore.json").writeText("{}")
        assertThrows(IllegalStateException::class.java) { runBlocking { service.importSeries(bytes) } }
        assertEquals(1, store.merged.size)
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
