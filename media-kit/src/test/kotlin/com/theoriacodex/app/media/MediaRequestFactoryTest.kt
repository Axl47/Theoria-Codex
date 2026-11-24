package com.theoriacodex.app.media

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import com.theoriacodex.domain.model.SourceKey
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaRequestFactoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `cached bytes are copied from the entry keyed by the normalized url, only within the size bounds (ADR 0108)`() {
        val context = RuntimeEnvironment.getApplication()
        val diskCache = DiskCache.Builder().directory(folder.newFolder("coil").toOkioPath()).build()
        fun seed(key: String, bytes: ByteArray) = diskCache.openEditor(key)!!.apply {
            diskCache.fileSystem.write(data) { write(bytes) }
            diskCache.fileSystem.write(metadata) { }
        }.commit()
        val page = ByteArray(10) { it.toByte() }
        seed("https://gelbooru.com/images/page.jpg", page)
        seed("https://video-cdn4.gelbooru.com/images/raw.jpg", page)
        seed("https://gelbooru.com/images/empty.jpg", ByteArray(0))
        Coil.setImageLoader(ImageLoader.Builder(context).diskCache(diskCache).build())
        try {
            val copy = folder.newFile()
            assertTrue(copyCachedMediaBytes(context, SourceKey.GELBOORU, "https://video-cdn4.gelbooru.com/images/page.jpg", copy, maxBytes = 10))
            assertArrayEquals(page, copy.readBytes())

            assertFalse(copyCachedMediaBytes(context, SourceKey.GELBOORU, "https://video-cdn4.gelbooru.com/images/page.jpg", folder.newFile(), maxBytes = 9))
            assertFalse(copyCachedMediaBytes(context, SourceKey.GELBOORU, "https://video-cdn4.gelbooru.com/images/raw.jpg", folder.newFile()))
            assertFalse(copyCachedMediaBytes(context, SourceKey.GELBOORU, "https://gelbooru.com/images/empty.jpg", folder.newFile()))
            assertFalse(copyCachedMediaBytes(context, SourceKey.GELBOORU, "https://gelbooru.com/images/missing.jpg", folder.newFile()))
        } finally {
            Coil.reset()
        }
    }

    @Test
    fun `building another provider request cannot replace protected source headers`() {
        val pixiv = request(SourceKey.PIXIV)
        val gelbooru = request(SourceKey.GELBOORU)
        assertEquals("https://www.pixiv.net/", pixiv.headers["Referer"])
        assertEquals("https://gelbooru.com/", gelbooru.headers["Referer"])
        assertEquals("Mozilla/5.0", pixiv.headers["User-Agent"])
        assertFalse(pixiv.allowHardware)
    }

    @Test
    fun `static and controllable gallery requests cannot reuse the ordinary animation cache entry`() {
        val normal = request(SourceKey.PIXIV)
        val static = request(SourceKey.PIXIV, static = true)
        val controllable = request(SourceKey.PIXIV, controllable = true)
        val key = ANIMATED_WEBP_DECODE_MODE_PARAMETER
        assertNull(normal.parameters.memoryCacheKey(key))
        assertEquals(AnimatedWebPDecodeMode.STATIC_FIRST_FRAME, static.parameters.value<AnimatedWebPDecodeMode>(key))
        assertEquals(AnimatedWebPDecodeMode.CONTROLLABLE, controllable.parameters.value<AnimatedWebPDecodeMode>(key))
        assertNotEquals(static.parameters.memoryCacheKey(key), controllable.parameters.memoryCacheKey(key))
    }

    private fun request(source: SourceKey, static: Boolean = false, controllable: Boolean = false) =
        MediaRequestFactory.imageRequest(RuntimeEnvironment.getApplication(), "https://media.example/image.webp",
            source, crossfade = false, staticAnimatedWebPFrame = static, controllableAnimatedWebP = controllable)
}
