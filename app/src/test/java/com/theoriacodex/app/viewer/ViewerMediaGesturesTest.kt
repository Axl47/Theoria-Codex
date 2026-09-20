package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerMediaGesturesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `assistive media actions use the current seek zoom info and chrome callbacks`() {
        val zoomed = mutableStateOf(false)
        val chrome = mutableStateOf(false)
        val exploration = mutableStateOf(false)
        val seekable = mutableStateOf(true)
        val active = mutableStateOf(true)
        val seekDeltas = mutableListOf<Long>()
        var infoOpened = 0
        compose.setContent {
            Box(Modifier.size(200.dp).then(viewerMediaGestureModifier(
                mediaKey = ViewerMediaKey(PostId(SourceKey.GELBOORU, "video"), 0),
                active = active.value, seekable = seekable.value, zoomed = zoomed.value,
                chromeVisible = chrome.value, touchExplorationEnabled = exploration.value,
                actions = ViewerMediaGestureActions(
                    seek = { seekDeltas += it }, zoom = { zoomed.value = !zoomed.value },
                    toggleControls = { chrome.value = !chrome.value }, showInfo = { infoOpened++ },
                ),
            )))
        }
        performAction("Rewind 10 seconds")
        performAction("Forward 10 seconds")
        performAction("Zoom in")
        performAction("Reset zoom")
        performAction("Show controls")
        compose.onNodeWithContentDescription("Media actions").performSemanticsAction(SemanticsActions.OnClick) {
            assertTrue(it())
        }
        compose.onNodeWithContentDescription("Media actions").performTouchInput {
            doubleClick(Offset(width * 0.9f, height * 0.5f))
        }
        compose.runOnIdle {
            assertEquals(listOf(-10_000L, 10_000L, 10_000L), seekDeltas)
            assertFalse(zoomed.value)
            assertTrue(chrome.value)
            assertEquals(1, infoOpened)
            exploration.value = true
            seekable.value = false
        }
        val labels = compose.onNodeWithContentDescription("Media actions").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Zoom in"), labels)
        compose.runOnIdle { active.value = false }
        compose.onNodeWithContentDescription("Media actions").assertDoesNotExist()
    }

    private fun performAction(label: String) {
        val action = compose.onNodeWithContentDescription("Media actions").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }
}
