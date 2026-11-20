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
    private val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build().apply {
        setMediaItem(MediaItem.fromUri("https://example.org/video.mp4"))
        setSeekParameters(SeekParameters.EXACT)
    }
    /** The player's position each time the preview stopped. */
    private val previewStops = mutableListOf<Long>()
    private val previews = ViewerScrubPreviews(player) { KeyframesEveryTwoSeconds { previewStops += player.currentPosition } }

    @After fun release() {
        previews.close()
        player.release()
    }

    @Test fun `a release on the shown frame stops the preview, then lands on its keyframe with later seeks exact`() = runTest {
        val drag = launch { previews.follow(MutableStateFlow(9_000L)) { _, _ -> } }
        runCurrent()

        assertEquals(8_000L, previews.seekReleased(9_000L))
        assertEquals(0L, previewStops.first())
        assertEquals(8_000L, player.currentPosition)
        assertEquals(SeekParameters.EXACT, player.seekParameters)
        drag.cancelAndJoin()
    }

    @Test fun `a release where no frame is shown yet, or after the drag, seeks exactly`() = runTest {
        val targets = MutableStateFlow(9_000L)
        val drag = launch { previews.follow(targets) { _, _ -> } }
        runCurrent()
        // The finger moved on; its frame waits until the finger rests there.
        targets.value = 13_000L
        assertEquals(13_000L, previews.seekReleased(13_000L))

        drag.cancelAndJoin()
        assertEquals(9_000L, previews.seekReleased(9_000L))
        assertEquals(9_000L, player.currentPosition)
    }
}

/** Frames of the keyframe at or before each target, on a 2 s keyframe interval; [onIdle] runs at each stop. */
private class KeyframesEveryTwoSeconds(private val onIdle: () -> Unit) : ScrubFrameSource {
    override suspend fun frameAt(positionMs: Long) =
        ScrubFrame(positionMs - positionMs % 2_000L, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    override fun idle() = onIdle()

    override fun close() = Unit
}
