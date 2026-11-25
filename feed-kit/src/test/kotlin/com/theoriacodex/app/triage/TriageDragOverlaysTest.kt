package com.theoriacodex.app.triage

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargetsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TriageDragOverlaysTest {
    @get:Rule val compose = createComposeRule()

    private val drag = TriageDrag()

    private fun showOverlays(removes: Boolean) = compose.setContent {
        MaterialTheme {
            TriageDragOverlays(
                drag,
                TriageTargets(emptyList(), listOf(CollectTarget("long_hair", "long hair", CollectTargetVisual.Icon(Icons.Outlined.VisibilityOff), "long_hair"))),
                CollectTargetsState(),
                CollectTargetsState(),
                SwipeThresholds(commitPx = 100f, flingPx = 100f, cuePx = 10f),
                removes,
            )
        }
    }

    private fun dragTo(offset: Offset) = compose.runOnIdle { drag.dragBy(offset - drag.offset, offset) }

    @Test fun `a sideways drag names what letting go there does`() {
        showOverlays(removes = false)
        dragTo(Offset(60f, 0f))
        compose.onNodeWithText("Like").assertExists()
        dragTo(Offset(-60f, 0f))
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("Like").assertDoesNotExist()
        dragTo(Offset(0f, 60f))
        compose.onNodeWithText("long hair").assertExists()
    }

    @Test fun `a removal deck names keep and remove`() {
        showOverlays(removes = true)
        dragTo(Offset(60f, 0f))
        compose.onNodeWithText("Keep").assertExists()
        dragTo(Offset(-60f, 0f))
        compose.onNodeWithText("Remove").assertExists()
    }

    @Test fun `a diagonal drag shows the side and bottom hints together and arms the dominant one`() {
        showOverlays(removes = false)
        dragTo(Offset(-110f, 60f))
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("long hair").assertExists()
        val hints = edgeHints(drag.offset, SwipeThresholds(commitPx = 100f, flingPx = 100f, cuePx = 10f))
        assertEquals(SwipeDirection.Left, hints.armed)
        assertTrue(hints.vertical in 0f..1f && hints.vertical > 0f)
    }
}
