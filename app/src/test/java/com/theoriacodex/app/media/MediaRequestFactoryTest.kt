package com.theoriacodex.app.media

import android.app.Application
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaRequestFactoryTest {
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
