package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.VideoVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityPolicyTest {
    private val media = ImageRef("original", null, "video/mp4", videoVariants = listOf(
        VideoVariant("original", original = true), VideoVariant("1080", 1080),
        VideoVariant("720", 720), VideoVariant("360", 360),
    ))

    @Test fun `network defaults and explicit choices select renditions of one item`() {
        assertEquals("original", media.withVideoQuality(VideoQuality.AUTO, false).url)
        assertEquals("720", media.withVideoQuality(VideoQuality.AUTO, true).url)
        assertEquals("360", media.withVideoQuality(VideoQuality.DATA_SAVER, false).url)
        assertEquals("original", media.withVideoQuality(VideoQuality.BEST, true).url)
        assertEquals("1080", media.withVideoQuality(VideoQuality.AUTO, true, 1080).url)
        assertEquals(media.videoVariants, media.withVideoQuality(VideoQuality.DATA_SAVER, true).videoVariants)
    }

    @Test fun `offline media and unavailable resolution retain usable media`() {
        val local = media.copy(localPath = "/offline/video.mp4")
        assertEquals(local, local.withVideoQuality(VideoQuality.DATA_SAVER, true))
        assertEquals(media, media.withVideoQuality(VideoQuality.AUTO, true, 2160))
        assertEquals("1080", media.copy(videoVariants = listOf(VideoVariant("1080", 1080)))
            .withVideoQuality(VideoQuality.DATA_SAVER, true).url)
    }
}
