package com.theoriacodex.app.viewer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PixivUgoiraArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid archive reports bounded compressed expanded and frame counts (ADR 0112)`() {
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
    fun `archive rejects unsafe names metadata mismatch and duplicate frame specs (ADR 0112)`() {
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
    fun `archive rejects compressed frame-count and per-frame expansion limits (ADR 0112)`() {
        val oversized = temporaryFolder.newFile("oversized.zip")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(UGOIRA_MAX_COMPRESSED_BYTES + 1L)
        }
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
                zip("many.zip", List(UGOIRA_MAX_FRAME_COUNT + 1) { index -> "$index.jpg" to byteArrayOf(1) }.toMap()),
                List(UGOIRA_MAX_FRAME_COUNT + 1) { index -> UgoiraFrameSpec("$index.jpg", 50) },
            )
        }
        assertThrows(IOException::class.java) {
            validateUgoiraArchive(expanded, listOf(UgoiraFrameSpec("frame.jpg", 50)))
        }
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
}
