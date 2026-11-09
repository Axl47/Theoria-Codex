package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerPageScrubberTest {
    @get:Rule val compose = createComposeRule()

    private val selected = mutableListOf<Int>()
    private val activity = mutableListOf<Boolean>()
    private val preview get() = compose.onNodeWithTag("viewer-scrub-preview")

    @Test fun `each page owns an equal share of the track, and mirroring flips it`() {
        assertEquals(0, viewerPageAt(0f, 120f, 12, mirrored = false))
        assertEquals(0, viewerPageAt(9.9f, 120f, 12, mirrored = false))
        assertEquals(1, viewerPageAt(10f, 120f, 12, mirrored = false))
        assertEquals(11, viewerPageAt(120f, 120f, 12, mirrored = false))
        assertEquals(11, viewerPageAt(0f, 120f, 12, mirrored = true))
        assertEquals(0, viewerPageAt(120f, 120f, 12, mirrored = true))
    }

    @Test fun `a landscape page and its neighbours are shrunk to fit a phone, a portrait one is left alone`() {
        val landscape = viewerPagePreviewFrame(16f / 9f, 344.dp)
        assertTrue("target is ${landscape.width}", landscape.width * 2.1f + 12.dp <= 344.01.dp)
        assertEquals(viewerScrubFrameSize(0.7f), viewerPagePreviewFrame(0.7f, 344.dp))
    }

    @Test fun `a drag steps through pages and turns the page once on release`() {
        setScrubber(pageIndex = 2)
        scrubber { down(at(0.1f)); moveTo(at(0.5f)) }
        preview.assertIsDisplayed()
        compose.onNodeWithText("7 / 12").assertIsDisplayed()
        compose.onNodeWithText("+4").assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }

        scrubber { moveTo(at(0.77f)); up() }
        preview.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(9), selected)
            assertEquals(listOf(true, false), activity)
        }
    }

    @Test fun `releasing on the starting page does not turn it`() {
        setScrubber(pageIndex = 6)
        scrubber { down(at(0.5f)); up() }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    @Test fun `a cancelled drag leaves the post on its page`() {
        setScrubber(pageIndex = 2)
        scrubber { down(at(0.1f)); moveTo(at(0.9f)); cancel() }
        preview.assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals(listOf(true, false), activity)
        }
    }

    @Test fun `removing a held scrubber cancels rather than turning the page`() {
        var shown by mutableStateOf(true)
        setScrubber(pageIndex = 2) { shown }
        scrubber { down(at(0.1f)); moveTo(at(0.9f)) }
        compose.runOnIdle { shown = false }
        compose.runOnIdle {
            assertTrue(selected.isEmpty())
            assertEquals(listOf(true, false), activity)
        }
    }

    @Test fun `instant seek turns the page at each new step without a preview`() {
        setScrubber(pageIndex = 0, instantSeek = true)
        scrubber { down(at(0.2f)); moveTo(at(0.21f)); moveTo(at(0.4f)) }
        preview.assertDoesNotExist()
        scrubber { up() }
        compose.runOnIdle { assertEquals(listOf(2, 4), selected) }
    }

    private fun TouchInjectionScope.at(fraction: Float) = percentOffset(fraction, 0.5f)

    private fun scrubber(gesture: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag("viewer-page-scrubber").performTouchInput(gesture)
    }

    private fun setScrubber(pageIndex: Int, instantSeek: Boolean = false, shown: () -> Boolean = { true }) {
        val items = List(12) { index -> ViewerMediaOverviewItem(index, ImageRef(null, "/missing/$index.jpg", "image/jpeg"), ViewerMediaOverviewKind.STILL_IMAGE, "/missing/$index.jpg") }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalViewerInstantSeek provides instantSeek) {
                    if (shown()) {
                        ViewerPageScrubber(SourceKey.PIXIV, items, 0.7f, pageIndex, false, { selected += it }, { activity += it }, Modifier.width(352.dp))
                    }
                }
            }
        }
    }
}
