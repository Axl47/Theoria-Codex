package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaTimelineBarTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private val playbackPosition = mutableStateOf(10_000L)
    private val seeks = mutableListOf<Long>()
    private var cancelled = 0
    private var dragging = false

    @Test fun `drag previews changing targets and seeks only once on release`() {
        showTimeline()
        track().performTouchInput { down(Offset(width * 0.25f, centerY)) }
        compose.onNodeWithTag("viewer-scrub-preview").assertIsDisplayed()
        compose.onNodeWithText("Frame 25000").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(dragging)
            assertTrue(seeks.isEmpty())
            assertEquals(10_000L, playbackPosition.value)
        }
        track().performTouchInput { moveTo(Offset(width * 0.75f, centerY)) }
        compose.onNodeWithText("Frame 75000").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            assertEquals(10_000L, playbackPosition.value)
        }
        track().performTouchInput { up() }
        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(75_000L), seeks)
            assertEquals(75_000L, playbackPosition.value)
            assertEquals(0, cancelled)
            assertTrue(!dragging)
        }
    }

    @Test fun `pointer cancellation discards preview and preserves playback position`() {
        showTimeline()
        track().performTouchInput { down(center); moveTo(Offset(width * 0.9f, centerY)) }
        compose.onNodeWithTag("viewer-scrub-preview").assertIsDisplayed()
        track().performTouchInput { cancel() }
        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        assertCancelledWithoutSeek()
    }

    @Test fun `removing a held timeline cancels rather than committing a seek`() {
        showTimeline()
        track().performTouchInput { down(center) }
        compose.onNodeWithTag("viewer-scrub-preview").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        assertCancelledWithoutSeek()
    }

    @Test fun `instant seek updates playback during a held drag without thumbnail previews`() {
        showTimeline(instantSeek = true)
        track().performTouchInput { down(Offset(width * 0.25f, centerY)) }
        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        compose.runOnIdle { assertEquals(25_000L, playbackPosition.value) }
        track().performTouchInput { moveTo(Offset(width * 0.75f, centerY)) }
        compose.onNodeWithTag("viewer-scrub-preview").assertDoesNotExist()
        compose.runOnIdle { assertEquals(75_000L, playbackPosition.value) }
        track().performTouchInput { up() }
        compose.runOnIdle { assertEquals(75_000L, seeks.last()); assertEquals(0, cancelled) }
    }

    private fun showTimeline(instantSeek: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalViewerInstantSeek provides instantSeek) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (visible.value) ViewerPlaybackFooter(Modifier.width(360.dp)) {
                        MediaTimelineBar(
                        positionMs = playbackPosition.value, durationMs = 100_000L,
                        onSeekStarted = { dragging = true },
                        onSeekFinished = { seeks += it; playbackPosition.value = it; dragging = false },
                        onSeekCancelled = { cancelled++; dragging = false },
                        preview = { Text("Frame $it") },
                        )
                    }
                }
                }
            }
        }
    }

    private fun track() = compose.onNodeWithTag("viewer-timeline-track")

    private fun assertCancelledWithoutSeek() = compose.runOnIdle {
        assertTrue(seeks.isEmpty())
        assertEquals(10_000L, playbackPosition.value)
        assertEquals(1, cancelled)
        assertTrue(!dragging)
    }
}
