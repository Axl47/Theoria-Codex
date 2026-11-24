package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.SourceKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What leaving the Viewer does to an episode's player, through the real screen and player pool. The episode's file is
 * missing, so each player bound to it fails once and never gets a duration or renders a frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ViewerVideoExitTest.PlayerApplication::class)
class ViewerVideoExitTest {
    @get:Rule val compose = createComposeRule()

    class PlayerApplication : Application(), VideoPlaybackInfrastructureOwner {
        override val videoPlaybackInfrastructure by lazy { VideoPlaybackInfrastructure(this) }
    }

    private val episode = post(
        "episode",
        SourceKey.HITOMI,
        listOf(ImageRef(url = null, localPath = null, mime = "video/mp4", streams = listOf(MediaStream("file:///missing/episode.mp4", "Server A")))),
    )
    private val owner = testViewerOwner()
    private val shown = mutableStateOf(true)

    /** One per player that failed, so one per player bound. */
    private val playerErrors = mutableListOf<String>()

    /** Every position a released player hands on. */
    private val releases = mutableListOf<Long>()

    @Before fun recordPlayers() {
        Diagnostics.install { area, message, _ -> if (area == "playback") playerErrors += message }
        ApplicationProvider.getApplicationContext<PlayerApplication>().videoPlaybackInfrastructure.feedPositionHandoff
            .bind(ViewerMediaKey(episode.id, 0)) { releases += it }
    }

    @After fun stopRecording() {
        Diagnostics.install { _, _, _ -> }
    }

    @Test fun `closing with the presentation retained keeps the episode's player`() {
        showEpisode(ViewerSeries.Watching())

        compose.runOnIdle { owner.clearSession(retainPresentation = true) }
        // A rebuilt player would fail on the missing file too.
        runCatching { compose.waitUntilSettled(1_000) { playerErrors.size > 1 } }
        assertEquals("Closing replaced the player", 1, playerErrors.size)
    }

    @Test fun `a player let go of before its start settles reports no position`() {
        showEpisode(ViewerSeries.Watching(mapOf(episode.id to 60_531L)))

        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        assertEquals("A player that never got to its start reported a position", emptyList<Long>(), releases)
    }

    /** Shows [episode] as a series' entry and waits for its player to fail. */
    private fun showEpisode(series: ViewerSeries.Watching) {
        owner.replaceSession(session("watching", listOf(episode)).copy(series = series))
        compose.setContent {
            val uiState by owner.state.collectAsState()
            MaterialTheme { if (shown.value) ViewerScreen(uiState = uiState, host = ViewerScreenHost(onAction = owner::onAction)) }
        }
        compose.waitUntilSettled(5_000) { playerErrors.size == 1 }
    }
}
