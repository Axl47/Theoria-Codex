package com.theoriacodex.app.viewer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PixivUgoiraArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid archive reports bounded compressed expanded and frame counts`() {
        val archive = zip(
            "valid.zip",
            mapOf("0001.jpg" to byteArrayOf(1, 2, 3), "0002.jpg" to byteArrayOf(4, 5)),
        )

        val stats = validateUgoiraArchive(
            archive,
            listOf(UgoiraFrameSpec("0001.jpg", 50), UgoiraFrameSpec("0002.jpg", 60)),
        )

        assertEquals(5L, stats.expandedBytes)
        assertEquals(2, stats.frameCount)
        assertEquals(archive.length(), stats.compressedBytes)
    }

    @Test
    fun `archive rejects unsafe names metadata mismatch and duplicate frame specs`() {
        val unsafe = zip("unsafe.zip", mapOf("../frame.jpg" to byteArrayOf(1)))
        val valid = zip("mismatch.zip", mapOf("frame.jpg" to byteArrayOf(1)))

        assertThrows(IOException::class.java) {
            validateUgoiraArchive(unsafe, listOf(UgoiraFrameSpec("../frame.jpg", 50)))
        }
        assertThrows(IOException::class.java) {
            validateUgoiraArchive(valid, listOf(UgoiraFrameSpec("other.jpg", 50)))
        }
        assertThrows(IOException::class.java) {
            validateUgoiraArchive(
                valid,
                listOf(UgoiraFrameSpec("frame.jpg", 50), UgoiraFrameSpec("frame.jpg", 50)),
            )
        }
    }

    @Test
    fun `archive rejects compressed frame-count and per-frame expansion limits`() {
        val oversized = temporaryFolder.newFile("oversized.zip")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(UGOIRA_MAX_COMPRESSED_BYTES + 1L)
        }
        val ordinary = zip("ordinary.zip", mapOf("frame.jpg" to byteArrayOf(1)))
        val expanded = temporaryFolder.newFile("expanded.zip")
        ZipOutputStream(FileOutputStream(expanded)).use { zip ->
            zip.putNextEntry(ZipEntry("frame.jpg"))
            val block = ByteArray(64 * 1024)
            repeat((UGOIRA_MAX_FRAME_EXPANDED_BYTES / block.size).toInt() + 1) {
                zip.write(block)
            }
            zip.closeEntry()
        }

        assertThrows(IOException::class.java) {
            validateUgoiraArchive(oversized, listOf(UgoiraFrameSpec("frame.jpg", 50)))
        }
        assertThrows(IOException::class.java) {
            validateUgoiraArchive(
                ordinary,
                List(UGOIRA_MAX_FRAME_COUNT + 1) { index -> UgoiraFrameSpec("$index.jpg", 50) },
            )
        }
        assertThrows(IOException::class.java) {
            validateUgoiraArchive(expanded, listOf(UgoiraFrameSpec("frame.jpg", 50)))
        }
    }

    @Test
    fun `ugoira client source retains disk single-flight size and weighted-cache boundaries`() {
        val source = repositoryFile(
            "app/src/main/java/com/theoriacodex/app/viewer/PixivUgoiraPlayer.kt",
        ).readText()
        val search = repositoryFile(
            "app/src/main/java/com/theoriacodex/app/search/SearchScreen.kt",
        ).readText()

        assertTrue(source.contains("archiveFlights"))
        assertTrue(source.contains("loadFlights"))
        assertTrue(source.contains("ZipFile(archive)"))
        assertTrue(search.contains("UgoiraSizeBucket.CARD"))
        assertTrue(source.contains("decodedCacheBytes"))
        assertFalse(source.contains("ZipInputStream(ByteArrayInputStream"))
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = temporaryFolder.newFile(name)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun repositoryFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("Could not locate repository root")
        }
        return File(current, path)
    }
}
