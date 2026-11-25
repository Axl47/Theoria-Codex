package com.theoriacodex.app.triage

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.saveMenuTargets
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargetsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val saveState = CollectTargetsState<SaveTarget>()
    private val saveTargets = saveMenuTargets(listOf(SaveSuggestion("codex-1", "Skies", emptyList()), SaveSuggestion("codex-2", "Seas", emptyList()))) { it }

    private fun showOverlays(removes: Boolean, save: List<CollectTarget<SaveTarget>> = emptyList()) = compose.setContent {
        MaterialTheme {
            TriageDragOverlays(
                drag,
                TriageTargets(save, listOf(CollectTarget("long_hair", "long hair", CollectTargetVisual.Icon(Icons.Outlined.VisibilityOff), "long_hair"))),
                saveState,
                CollectTargetsState(),
                SwipeThresholds(commitPx = 100f, flingPx = 100f, cuePx = 10f),
                removes,
            )
        }
    }

    private fun dragTo(offset: Offset) = compose.runOnIdle { drag.dragBy(offset - drag.offset, offset) }

    @Test fun `a release picks the hovered target, else the default once committed, else nothing`() {
        val (skies, seas, new) = saveTargets
        val isCodex = { target: SaveTarget -> target is SaveTarget.Codex }
        assertEquals(seas, releasePick(seas, saveTargets, committed = true, isDefault = isCodex))
        assertEquals(new, releasePick(new, saveTargets, committed = false, isDefault = isCodex))
        assertEquals(skies, releasePick(null, saveTargets, committed = true, isDefault = isCodex))
        assertNull(releasePick(null, saveTargets, committed = false, isDefault = isCodex))
        assertNull(releasePick(null, saveTargets.drop(2), committed = true, isDefault = isCodex))
    }

    @Test fun `an upward drag past the commit distance arms the default codex until it drops back`() {
        showOverlays(removes = false, save = saveTargets)
        dragTo(Offset(0f, -60f))
        compose.runOnIdle { assertNull(saveState.armed) }
        dragTo(Offset(0f, -110f))
        compose.runOnIdle { assertEquals("codex:codex-1", saveState.armed?.key) }
        dragTo(Offset(0f, -60f))
        compose.runOnIdle { assertNull(saveState.armed) }
        dragTo(Offset(-110f, -60f))
        compose.runOnIdle { assertNull(saveState.armed) }
    }

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

    @Test fun `a diagonal drag keeps both hints visible and dims the one a release would not commit to`() {
        showOverlays(removes = false)
        val thresholds = SwipeThresholds(commitPx = 100f, flingPx = 100f, cuePx = 10f)
        dragTo(Offset(-110f, 105f))
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("long hair").assertExists()
        val sideways = edgeHints(drag.offset, thresholds)
        assertEquals(SwipeDirection.Left, sideways.armed)
        assertEquals(1f, sideways.sideways)
        assertTrue(sideways.vertical in 0.1f..0.5f)
        dragTo(Offset(-105f, 110f))
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("long hair").assertExists()
        val down = edgeHints(drag.offset, thresholds)
        assertEquals(SwipeDirection.Down, down.armed)
        assertEquals(1f, down.vertical)
        assertTrue(down.sideways in 0.1f..0.5f)
    }
}
