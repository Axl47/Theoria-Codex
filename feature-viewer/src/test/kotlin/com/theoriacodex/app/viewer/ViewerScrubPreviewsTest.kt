@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerScrubPreviewsTest {
    private val player = SeekRecordingPlayer(
        ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build().apply {
            setMediaItem(MediaItem.fromUri("https://example.org/video.mp4"))
            setSeekParameters(SeekParameters.EXACT)
        },
    )
    private val previewStops = mutableListOf<Long>()
    private val previews = ViewerScrubPreviews(player) { KeyframesEveryTwoSeconds { previewStops += player.currentPosition } }

    @After fun release() {
        previews.close()
        player.release()
    }

    @Test fun `a release on the shown frame stops the preview, then lands on that frame with later seeks exact (ADR 0176)`() = runTest {
        val drag = launch { previews.follow(MutableStateFlow(9_000L)) { _, _ -> } }
        runCurrent()

        assertEquals(8_000L, previews.seekReleased(9_000L))
        assertEquals(0L, previewStops.first())
        assertEquals(listOf(SHOWN_FRAME_SEEK to 8_000L), player.seeks)
        assertEquals(SeekParameters.EXACT, player.seekParameters)
        drag.cancelAndJoin()
    }

    @Test fun `the shown frame's seek takes a keyframe its truncated time hides, else lands exactly (ADR 0176)`() {
        assertEquals(7_999_600L, SHOWN_FRAME_SEEK.resolveSeekPositionUs(7_999_000L, 7_999_600L, 9_999_600L))
        assertEquals(9_000_000L, SHOWN_FRAME_SEEK.resolveSeekPositionUs(9_000_000L, 8_000_000L, 10_000_000L))
    }

    @Test fun `a release where no frame is shown yet, or after the drag, seeks exactly`() = runTest {
        val targets = MutableStateFlow(9_000L)
        val drag = launch { previews.follow(targets) { _, _ -> } }
        runCurrent()
        targets.value = 13_000L
        assertEquals(13_000L, previews.seekReleased(13_000L))

        drag.cancelAndJoin()
        assertEquals(9_000L, previews.seekReleased(9_000L))
        assertEquals(9_000L, player.currentPosition)
    }
}

private class SeekRecordingPlayer(private val player: ExoPlayer) : ExoPlayer by player {
    val seeks = mutableListOf<Pair<SeekParameters, Long>>()

    override fun seekTo(positionMs: Long) {
        seeks += player.seekParameters to positionMs
        player.seekTo(positionMs)
    }
}

private class KeyframesEveryTwoSeconds(private val onIdle: () -> Unit) : ScrubFrameSource {
    override suspend fun frameAt(positionMs: Long) =
        ScrubFrame(positionMs - positionMs % 2_000L, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    override fun idle() = onIdle()

    override fun close() = Unit
}
