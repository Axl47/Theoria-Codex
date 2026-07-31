package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPlaybackSemanticsTest {
    @Test
    fun `each player emits at most one first-frame trace marker`() {
        val recorded = mutableListOf<String>()
        val previewGate = FirstFrameTraceGate(recorded::add)
        val viewerGate = FirstFrameTraceGate(recorded::add)

        repeat(4) {
            previewGate.recordOnce(MediaTraceSections.PREVIEW_FIRST_FRAME)
            viewerGate.recordOnce(MediaTraceSections.VIEWER_FIRST_FRAME)
        }

        assertEquals(
            listOf(
                MediaTraceSections.PREVIEW_FIRST_FRAME,
                MediaTraceSections.VIEWER_FIRST_FRAME,
            ),
            recorded,
        )
    }

    @Test
    fun `normal cards do not publish benchmark playback accessibility state`() {
        assertNull(
            playbackDiagnosticsSemantics(
                enabled = false,
                isPlaying = true,
                surface = "Search",
            ),
        )
        assertNull(
            playbackDiagnosticsSemantics(
                enabled = false,
                isPlaying = false,
                surface = "Viewer",
            ),
        )
    }

    @Test
    fun `benchmark playback state is concise and contains no provider identity`() {
        assertEquals(
            PlaybackDiagnosticsSemantics(
                contentDescription = "Playing Search benchmark video",
                stateDescription = "Playing",
            ),
            playbackDiagnosticsSemantics(
                enabled = true,
                isPlaying = true,
                surface = "Search",
            ),
        )
    }

    @Test
    fun `viewer video semantics use a stable resource-safe post identity`() {
        assertEquals(
            "viewer_video_rule34video_benchmark_viewer_3",
            viewerVideoTestTag(PostId(SourceKey.RULE34VIDEO, "Benchmark Viewer/3")),
        )
    }

    @Test
    fun `media trace names remain stable for benchmark comparison`() {
        assertEquals("TheoriaPreviewPrepare", MediaTraceSections.PREVIEW_PREPARE)
        assertEquals("TheoriaPreviewFirstFrame", MediaTraceSections.PREVIEW_FIRST_FRAME)
        assertEquals("TheoriaViewerPrepare", MediaTraceSections.VIEWER_PREPARE)
        assertEquals("TheoriaViewerFirstFrame", MediaTraceSections.VIEWER_FIRST_FRAME)
        assertEquals("TheoriaMediaLoad", MediaTraceSections.MEDIA_LOAD)
    }
}
