package com.theoriacodex.app.viewer

import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.VideoVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaybackRequestTest {
    private val source = SourceKey.GELBOORU
    private val media = ImageRef("original", null, "video/mp4", videoVariants = listOf(
        VideoVariant("original", original = true), VideoVariant("1080", 1080),
        VideoVariant("720", 720), VideoVariant("360", 360),
    ))

    @Test fun `media without streams plays the rendition its quality picks, with its source's headers, through the cache`() {
        fun location(ref: ImageRef, quality: VideoQuality, metered: Boolean, height: Int? = null) =
            ref.viewerPlayback(source, quality, metered, height)?.location
        assertEquals("720", location(media, VideoQuality.AUTO, metered = true))
        assertEquals("original", location(media, VideoQuality.AUTO, metered = false))
        assertEquals("360", location(media, VideoQuality.DATA_SAVER, metered = false))
        assertEquals("1080", location(media, VideoQuality.AUTO, metered = true, height = 1080))
        assertEquals("/saved.mp4", location(media.copy(localPath = "/saved.mp4"), VideoQuality.AUTO, metered = true))
        assertNull(ImageRef(null, null, "video/mp4").viewerPlayback(source, VideoQuality.AUTO, metered = false))

        assertEquals(
            VideoPlaybackRequest("720", source.requestHeaders(), mime = "video/mp4"),
            media.viewerPlayback(source, VideoQuality.AUTO, metered = true, streamLabel = "ignored"),
        )
    }

    @Test fun `a rendition's own mime goes with it, so a source whose addresses hide the type still plays`() {
        val hls = ImageRef("https://cdn.test/play?id=1", null, "video/mp4", videoVariants = listOf(
            VideoVariant("https://cdn.test/play?id=1", 1080, mime = "application/x-mpegURL"),
        ))

        assertEquals("application/x-mpegURL", hls.viewerPlayback(source, VideoQuality.BEST, metered = false)?.mime)
    }

    @Test fun `an episode plays the stream with the remembered label, else its source's first, outside the cache`() {
        val subtitles = listOf(MediaTrack("https://subs.test/en.vtt", "English"))
        val first = MediaStream("https://a.test/master.m3u8", "1080p · A", mime = "application/x-mpegURL", subtitles = subtitles)
        val second = MediaStream("https://b.test/video.mp4", "720p · B", audioTracks = listOf(MediaTrack("https://b.test/ja.m4a", "Japanese")))
        val episode = ImageRef(null, null, null, streams = listOf(first, second))

        val chosen = episode.viewerPlayback(source, VideoQuality.AUTO, metered = false, streamLabel = "720p · B")
        assertEquals(VideoPlaybackRequest(second.url, emptyMap(), audioTracks = second.audioTracks, cache = false), chosen)

        val fallback = episode.viewerPlayback(source, VideoQuality.DATA_SAVER, metered = true, height = 360, streamLabel = "gone")
        assertEquals(VideoPlaybackRequest(first.url, emptyMap(), mime = first.mime, subtitles = subtitles, cache = false), fallback)
    }

    @Test fun `a stream sends exactly its own headers, and none of its source's when it has none`() {
        val headers = mapOf("Referer" to "https://host.test/")
        fun sent(stream: MediaStream) = ImageRef(null, null, null, streams = listOf(stream)).viewerPlayback(source, VideoQuality.AUTO, false)?.headers

        assertEquals(headers, sent(MediaStream("https://host.test/v.mp4", "Host", headers = headers)))
        assertEquals(emptyMap<String, String>(), sent(MediaStream("https://host.test/v.mp4", "Host")))
    }

    @Test fun `an episode saved on the device plays its file`() {
        val stream = MediaStream("https://a.test/v.mp4", "A")
        val saved = ImageRef(null, "/saved.mp4", "video/mp4", streams = listOf(stream))

        assertEquals(VideoPlaybackRequest("/saved.mp4", source.requestHeaders(), mime = "video/mp4"), saved.viewerPlayback(source, VideoQuality.AUTO, false))
    }
}
