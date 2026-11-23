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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PixivUgoiraFramesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the tagger's sample spreads frames through the animation and decodes them no smaller than asked`() = runTest {
        val archive = temporaryFolder.newFile("sample.zip")
        // Frame i is 64 + i px wide, so the decoded widths name the frames picked.
        val specs = List(12) { UgoiraFrameSpec("$it.png", 50) }
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            specs.forEachIndexed { index, spec ->
                val bitmap = Bitmap.createBitmap(64 + index, 64, Bitmap.Config.ARGB_8888)
                zip.putNextEntry(ZipEntry(spec.fileName))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }

        assertEquals(listOf(65, 67, 69, 71, 73, 75), sampleUgoiraFrames(archive, specs, count = 6, sizePx = 64).map { it.width })
        // Halved twice, a 64 px frame is still 16 px.
        assertEquals(listOf(16, 16), sampleUgoiraFrames(archive, specs, count = 2, sizePx = 16).map { it.height })
    }

    @Test
    fun `a frame held longer is sampled once per stretch of time it fills`() {
        val held = listOf(UgoiraFrameSpec("0.png", 3_000)) + List(3) { UgoiraFrameSpec("${it + 1}.png", 1_000) }
        assertEquals(listOf(0, 0, 0, 1, 2, 3), ugoiraSampleIndices(held, count = 6))

        // One frame on screen for a minute among quick transitions is all six samples, not none of them.
        val pause = listOf(UgoiraFrameSpec("0.png", 60_000)) + List(11) { UgoiraFrameSpec("${it + 1}.png", 16) }
        assertEquals(List(6) { 0 }, ugoiraSampleIndices(pause, count = 6))
    }

    @Test
    fun `decoder publishes the first frame immediately and reuses it for complete playback`() = runTest {
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
        assertEquals(listOf(1, 8), batches.map { it.frames.size })
        assertEquals(12, batches.first().frameDelaysMs.size)
        assertFalse(batches.first().isComplete)
        assertTrue(complete.isComplete)
        assertEquals(12, complete.frames.size)
        assertEquals(8, batches.first().frames.first().bitmap.width)
        assertFalse(batches.first().frames.first().bitmap.isRecycled)
        assertSame(batches.first().frames.first().bitmap, complete.frames.first().bitmap)
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

    @Test
    fun `disk metadata the Gson writer stored still matches its archive`() {
        val archive = temporaryFolder.newFile("ugoira-123.zip").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            setLastModified(1_700_000_000_000L)
        }
        ugoiraMetadataFile(archive).writeText(
            checkNotNull(javaClass.getResource("/golden/ugoira-cache/gson-written.json")).readText(),
        )
        assertEquals(
            listOf(UgoiraFrameSpec("000000.jpg", 80), UgoiraFrameSpec("000001 & <b>.jpg", 16)),
            readUgoiraFrameMetadata(archive),
        )
    }
}
