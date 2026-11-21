package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.flow.flowOf
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
class CodexGridReorderUiTest {
    @get:Rule val compose = createComposeRule()

    private val sort = mutableStateOf(CodexLibrarySort.MANUAL)
    private var committed: List<String>? = null

    private fun showLibrary(
        deletes: CodexListDeletes = CodexListDeletes(),
        actionCodexId: String? = null,
        renameInPlace: Boolean = false,
    ) = compose.setContent { MaterialTheme {
        CodexListScreen(
            listOf(Codex("a", "Alpha", 1), Codex("b", "Beta", 2), Codex("c", "Gamma", 3)), emptyMap(), emptyMap(),
            { flowOf(CodexActionOptions()) }, {}, {}, {}, {}, {},
            onCommitReorder = { committed = it }, onCreateCodex = {}, onRenameCodex = { _, _ -> },
            onSetAutomaticTag = { _, _, _ -> }, onDeleteCodex = {}, likesCodexId = "likes",
            onCleanCodex = { _, _ -> }, librarySort = sort.value, onLibrarySortChange = { sort.value = it },
            initialActionCodexId = actionCodexId, deletes = deletes, renameInPlace = renameInPlace,
        )
    } }

    @Test fun `a Codex waiting to be deleted keeps its cell, drawn as its Undo`() {
        var acted: String? = null
        showLibrary(CodexListDeletes(mapOf("b" to PendingRemoval("Deleted Beta")), act = { acted = it }))
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Deleted Beta").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals("b", acted) }
    }

    @Test fun `the menu of a Codex whose screen renames in place has no Rename row`() {
        showLibrary(actionCodexId = "a", renameInPlace = true)
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun `under Manual a long-press drag drops the tile into the cell it crosses and commits once`() {
        showLibrary()
        val centre = { name: String -> compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.center }
        val travel = centre("Beta") - centre("Alpha")
        compose.onNodeWithText("Alpha").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(DRAG_STEPS) { moveBy(travel / DRAG_STEPS.toFloat()) }
            up()
        }
        compose.runOnIdle { assertEquals(listOf("b", "a", "c"), committed) }
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun `under Manual releasing a lifted tile where it was picked up opens its menu`() {
        showLibrary()
        compose.onNodeWithText("Beta").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.runOnIdle { assertNull(committed) }
    }

    @Test fun `TalkBack moves a tile one place at a time under Manual only`() {
        showLibrary()
        assertEquals(listOf("Move later"), moveLabels("Alpha"))
        assertEquals(listOf("Move earlier", "Move later"), moveLabels("Beta"))
        compose.runOnIdle { assertTrue(moves("Gamma").single { it.label == "Move earlier" }.action()) }
        compose.runOnIdle { assertEquals(listOf("a", "c", "b"), committed) }

        sort.value = CodexLibrarySort.NAME_ASCENDING
        compose.waitForIdle()
        assertEquals(emptyList<String>(), moveLabels("Alpha"))
    }

    @Test fun `choosing Manual in the sort menu only selects it`() {
        sort.value = CodexLibrarySort.NAME_DESCENDING
        showLibrary()
        compose.onNodeWithContentDescription("Sort Codices").performClick()
        compose.onNodeWithText("Manual").performClick()
        compose.runOnIdle { assertEquals(CodexLibrarySort.MANUAL, sort.value) }
        compose.onNodeWithContentDescription("Sort Codices").assertIsDisplayed()
        val left = { name: String -> compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.left }
        assertTrue(left("Alpha") < left("Beta"))
        compose.runOnIdle { assertNull(committed) }
    }

    private fun moves(name: String) =
        compose.onNodeWithText(name).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

    private fun moveLabels(name: String) = moves(name).map { it.label }

    private companion object {
        const val DRAG_STEPS = 12
    }
}
