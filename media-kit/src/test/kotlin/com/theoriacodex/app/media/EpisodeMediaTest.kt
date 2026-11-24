package com.theoriacodex.app.media

import com.theoriacodex.app.viewer.VideoPlaybackRequest
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.app.viewer.viewerPlayback
import com.theoriacodex.data.repository.episodePost
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeMediaTest {
    private val series = Series(SeriesId(SourceKey.parseOrThrow("custom_aniyomi_1"), "/anime/1"), "Anime")
    private val entry = SeriesEntry("/e/1", "Episode 1")
    private val stream = MediaStream(
        "https://video.test/master.m3u8", "1080p", "application/x-mpegURL", headers = mapOf("Referer" to "https://host.test/"),
    )

    @Test fun `an episode with streams and no address is a video the Viewer plays from its stream`() {
        val post = episodePost(series, entry, "https://covers.test/1.jpg", listOf(stream))
        val media = postMediaItems(post).single()

        assertNull(media.url)
        assertTrue(isVideoMediaRef(media))
        assertTrue(isAnimatedPost(post))
        assertFalse(requiresLazyMediaResolution(post))
        assertEquals(
            VideoPlaybackRequest(stream.url, stream.headers, mime = stream.mime, cache = false),
            media.viewerPlayback(post.id.source, VideoQuality.AUTO, metered = false),
        )
        assertNull(authoritativeDurationProbeRef(post))
        assertEquals("https://covers.test/1.jpg", postDownloadMediaCandidate(post)?.url)
        assertNull(postMotion(post))
    }

    @Test fun `an episode whose streams are not fetched yet resolves lazily`() {
        assertTrue(requiresLazyMediaResolution(episodePost(series, entry, "https://covers.test/1.jpg")))
    }
}
