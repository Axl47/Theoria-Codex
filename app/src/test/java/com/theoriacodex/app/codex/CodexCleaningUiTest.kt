package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CodexCleaningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `collection action selects multiple comparisons while excluding target and removed choices`() {
        val likesId = ProfileLibraryIds.likes("profile-main")
        val codices = mutableStateOf(listOf(Codex("target", "Collection", 1), Codex("a", "Source A", 2), Codex("b", "Source B", 3)))
        var submitted: Pair<String, Set<String>>? = null
        compose.setContent { MaterialTheme {
            CodexListScreen(
                codices = codices.value, itemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                observeActionOptions = { flowOf(CodexActionOptions()) }, onOpenCodex = {}, onImportCodex = {},
                onDownloadCodex = {}, onShareCodex = {}, onSearchFromCodex = { _, _, _ -> }, onCommitReorder = {},
                onCreateCodex = {}, onRenameCodex = { _, _ -> }, onSetAutomaticTag = { _, _, _ -> }, onDeleteCodex = {},
                likesCodexId = likesId, onCleanCodex = { id, sources -> submitted = id to sources },
            )
        } }
        compose.onNodeWithContentDescription("Actions for Collection").performClick()
        compose.onNodeWithContentDescription("Clean Codex").performClick()
        compose.onNodeWithText("Clean Collection").assertIsDisplayed()
        compose.onNode(hasText("Collection") and isToggleable()).assertDoesNotExist()
        compose.onNodeWithText("Matching posts will also be unliked.").assertDoesNotExist()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.onNode(hasText("Source B") and isToggleable()).performClick()
        compose.onNodeWithText("Clean Codex").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("target" to setOf("a", "b"), submitted) }

        compose.onNodeWithContentDescription("Actions for Collection").performClick()
        compose.onNodeWithContentDescription("Clean Codex").performClick()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.runOnIdle { codices.value = codices.value.filterNot { it.codexId == "a" }; submitted = null }
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(submitted) }
    }

    @Test fun `cleaning Likes warns about unliking and excludes itself from comparisons`() {
        val likes = Codex(ProfileLibraryIds.likes("profile-main"), "Likes", 1)
        var submitted: Set<String>? = null
        compose.setContent { MaterialTheme {
            CodexCleaningSheet(
                target = likes,
                codices = listOf(likes, Codex("a", "Source A", 2)),
                isLikesCodex = true,
                onDismiss = {},
                onClean = { submitted = it },
            )
        } }
        compose.onNodeWithText("Clean Likes").assertIsDisplayed()
        compose.onNodeWithText("Matching posts will also be unliked.").assertIsDisplayed()
        compose.onNode(hasText("Likes") and isToggleable()).assertDoesNotExist()
        compose.onNodeWithText("Clean Codex").assertIsNotEnabled()
        compose.onNode(hasText("Source A") and isToggleable()).performClick()
        compose.onNodeWithText("Clean Codex").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("a"), submitted) }
    }
}
