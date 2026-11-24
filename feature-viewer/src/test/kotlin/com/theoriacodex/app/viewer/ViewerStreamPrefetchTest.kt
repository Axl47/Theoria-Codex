package com.theoriacodex.app.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerStreamPrefetchTest {
    @Test
    fun `an episode, which plays outside the shared video cache, is never warmed into it`() = runTest {
        val stream = MediaStream("https://video.test/episode.mp4", "1080p")
        val episode = ViewerMediaState(
            key = ViewerMediaKey(PostId(SourceKey.parseOrThrow("custom_aniyomi_1"), "/e/1"), 0),
            ref = ImageRef(stream.url, null, null, streams = listOf(stream)),
            kind = ViewerMediaKind.VIDEO,
            displayLocation = stream.url,
        )

        val result = prefetchViewerMedia(ApplicationProvider.getApplicationContext(), episode, VideoQuality.AUTO)

        assertEquals(ViewerPrefetchOutcome.SKIPPED, result.outcome)
    }
}
