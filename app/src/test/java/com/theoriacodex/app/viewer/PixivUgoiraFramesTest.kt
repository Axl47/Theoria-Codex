package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PixivUgoiraFramesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `decoder publishes usable frames before the complete animation`() = runTest {
        val archive = temporaryFolder.newFile("frames.zip")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val specs = List(12) { UgoiraFrameSpec("$it.png", 50) }
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            specs.forEach { spec ->
                zip.putNextEntry(ZipEntry(spec.fileName)); zip.write(bytes); zip.closeEntry()
            }
        }
        validateUgoiraArchive(archive, specs)
        val batches = mutableListOf<UgoiraPlayback>()
        val complete = decodeUgoiraFrames(archive, specs, UgoiraSizeBucket.CARD) { batches += it }
        assertEquals(4, batches.first().frames.size)
        assertEquals(12, batches.first().frameDelaysMs.size)
        assertFalse(batches.first().isComplete)
        assertTrue(complete.isComplete)
        assertEquals(12, complete.frames.size)
        assertEquals(8, batches.first().frames.first().bitmap.width)
        assertFalse(batches.first().frames.first().bitmap.isRecycled)
    }

    @Test
    fun `long animations fit decoded budgets while exports keep original resolution`() {
        for (bucket in listOf(UgoiraSizeBucket.CARD, UgoiraSizeBucket.VIEWER)) {
            val sample = ugoiraFrameSampleSize(1200, 1200, 400, bucket)
            val dimension = (1200 + sample - 1) / sample
            assertTrue(dimension.toLong() * dimension * 4 * 400 <= ugoiraPlaybackBudget(bucket))
        }
        assertEquals(1, ugoiraFrameSampleSize(1200, 1200, 400, UgoiraSizeBucket.EXPORT))
    }

    @Test
    fun `disk metadata rejects damaged or replaced cache pairs`() {
        val archive = temporaryFolder.newFile("cached.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val specs = listOf(UgoiraFrameSpec("frame.jpg", 50))
        writeUgoiraFrameMetadata(archive, specs)
        assertEquals(specs, readUgoiraFrameMetadata(archive))
        archive.appendBytes(byteArrayOf(4))
        assertNull(readUgoiraFrameMetadata(archive))
        writeUgoiraFrameMetadata(archive, specs)
        ugoiraMetadataFile(archive).writeText("broken")
        assertNull(readUgoiraFrameMetadata(archive))
    }
}
