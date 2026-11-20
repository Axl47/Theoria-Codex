package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexReorderControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `move actions follow collection identity and commit the reordered draft`() {
        val codices = listOf(Codex("a", "A", 1), Codex("b", "B", 2), Codex("c", "C", 3))
        val state = CodexListUiState(codices)
        state.ordering.toggle(codices) {}
        compose.setContent { MaterialTheme { Column {
            state.ordering.reorderDraft.forEachIndexed { index, codex -> CodexReorderHandle(codex, index, state) }
        } } }
        assertEquals(listOf("Move down"), actions("A").map { it.label })
        val moveDown = actions("A").single()
        compose.runOnIdle { assertTrue(moveDown.action()) }
        val moved = compose.onNodeWithContentDescription("Reorder A").fetchSemanticsNode().config
        assertEquals("Position 2 of 3", moved[SemanticsProperties.StateDescription])
        assertEquals(listOf("Move up", "Move down"), actions("A").map { it.label })
        compose.onNodeWithContentDescription("Reorder A").performClick()
        compose.onNodeWithText("Move down").performClick()
        assertEquals(listOf("Move up"), actions("A").map { it.label })
        compose.runOnIdle {
            assertEquals(listOf("b", "c", "a"), state.ordering.reorderDraft.map { it.codexId })
            assertFalse(state.ordering.move("a", 1))
            assertFalse(state.ordering.move("missing", 1))
            var committed = emptyList<String>()
            state.ordering.toggle(codices) { committed = it }
            assertEquals(listOf("b", "c", "a"), committed)
            assertFalse(state.ordering.move("a", -1))
        }
    }

    @Test fun `automatic sort hides handles and Done keeps the manual order underneath`() {
        val sort = mutableStateOf(CodexLibrarySort.MANUAL)
        var committed = emptyList<String>()
        val manual = listOf(Codex("z", "Zoo", 1), Codex("a", "Art", 2))
        compose.setContent { MaterialTheme {
            CodexListScreen(
                manual, emptyMap(), emptyMap(), { flowOf(CodexActionOptions()) }, {}, {}, {}, {}, { _, _, _ -> },
                onCommitReorder = { committed = it }, onCreateCodex = {}, onRenameCodex = { _, _ -> },
                onSetAutomaticTag = { _, _, _ -> }, onDeleteCodex = {}, likesCodexId = "likes",
                onCleanCodex = { _, _ -> }, librarySort = sort.value, onLibrarySortChange = { sort.value = it },
            )
        } }
        compose.onNodeWithContentDescription("Reorder Codices").performClick()
        compose.onNodeWithContentDescription("Reorder Zoo").assertExists()
        compose.onNodeWithText("Sort: Manual").performClick()
        compose.onNodeWithText("Name (A–Z)").performClick()
        compose.onNodeWithText("Sort: Name (A–Z)").assertExists()
        compose.onNodeWithContentDescription("Reorder Zoo").assertDoesNotExist()
        val top = { name: String -> compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Art") < top("Zoo"))
        compose.onNodeWithContentDescription("Done reordering Codices").performClick()
        compose.runOnIdle { assertEquals(listOf("z", "a"), committed) }
    }

    private fun actions(name: String) = compose.onNodeWithContentDescription("Reorder $name")
        .fetchSemanticsNode().config[SemanticsActions.CustomActions]
}
