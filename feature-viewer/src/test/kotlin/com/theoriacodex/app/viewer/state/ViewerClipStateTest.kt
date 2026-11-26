package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerClipStateTest {
    private val identity = ViewerSessionIdentity("watching")

    @Test
    fun `clipping starts only on a watched episode with streams (ADR 0223)`() {
        assertNotNull(start(episodes("e1"), ViewerSeries.Watching()).controls.clip)
        assertNull(start(episodes("e1"), series = null).controls.clip)
        assertNull(start(listOf(post("still", ImageRef("https://example.test/a.jpg", null, "image/jpeg"))), ViewerSeries.Watching()).controls.clip)
    }

    @Test
    fun `saving emits one clip for the episode and leaves clip mode (ADR 0223)`() {
        val range = EpisodeClipRange(10_000L, 40_000L)
        val started = start(episodes("e1"), ViewerSeries.Watching())
        val ranged = reduceViewerState(started, ViewerAction.SetClipRange(range)).state

        val saved = reduceViewerState(ranged, ViewerAction.SaveClip("English", "Japanese"))

        assertNull(saved.state.controls.clip)
        assertEquals(
            listOf(ViewerEffect.ClipEpisode(identity, checkNotNull(started.currentMedia).key, range, "English", "Japanese")),
            saved.effects,
        )
    }

    @Test
    fun `moving to another episode drops the selection (ADR 0223)`() {
        val started = start(episodes("e1", "e2"), ViewerSeries.Watching())

        assertNull(reduceViewerState(started, ViewerAction.SelectPage(1)).state.controls.clip)
        assertNull(reduceViewerState(started, ViewerAction.CancelClip).state.controls.clip)
    }

    private fun start(posts: List<Post>, series: ViewerSeries?): ViewerUiState {
        val opened = reduceViewerState(ViewerUiState.Empty, ViewerAction.ReplaceSession(identity, posts, series = series)).state
        return reduceViewerState(opened, ViewerAction.StartClip).state
    }

    private fun episodes(vararg ids: String) = ids.map { id ->
        post(id, ImageRef(null, null, "video/mp4", streams = listOf(MediaStream("https://example.test/$id.m3u8", "Main"))))
    }

    private fun post(id: String, media: ImageRef) = Post(
        PostId(SourceKey.HITOMI, id), media, media, listOf(media), null, null, null, emptyList(), emptyList(), null, null, title = id,
    )
}
