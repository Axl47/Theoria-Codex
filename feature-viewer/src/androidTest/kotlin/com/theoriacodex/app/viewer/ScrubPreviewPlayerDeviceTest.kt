package com.theoriacodex.app.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrubPreviewPlayerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aDragOnAClipWithOneKeyframePreviewsNearTheFingerAndAnswersEverySeek_adr_0176() {
        val clip = File(context.cacheDir, CLIP)
        instrumentation.context.assets.open(CLIP).use { input -> clip.outputStream().use { input.copyTo(it) } }
        val request = VideoPlaybackInfrastructure(context).bind(VideoPlaybackRequest(clip.absolutePath, emptyMap(), cache = false))
        runBlocking(Dispatchers.Main) {
            val preview = ScrubPreviewPlayer(context, request, PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX)
            try {
                val landed = preview.frameMs(3_500L)
                assertTrue("landed at $landed", landed in 3_450L..3_500L)
                assertEquals("a seek that leaves the player in place", landed, preview.frameMs(3_500L))
                preview.idle()
                val afterStop = preview.frameMs(1_200L)
                assertTrue("landed at $afterStop after a stop", afterStop in 1_150L..1_200L)
            } finally {
                preview.close()
            }
        }
    }

    private suspend fun ScrubFrameSource.frameMs(targetMs: Long): Long = withTimeout(ANSWER_TIMEOUT_MS) { frameAt(targetMs) }.positionMs

    private companion object {
        const val CLIP = "single-keyframe-clip.mp4"
        const val PREVIEW_WIDTH_PX = 160
        const val PREVIEW_HEIGHT_PX = 90
        const val ANSWER_TIMEOUT_MS = 3_000L
    }
}
