package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.recommend.ForYouSourceSelector
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.settings.SettingsSection
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.settings.SettingsSectionExpansionState
import com.theoriacodex.app.settings.SettingsSectionKey
import com.theoriacodex.app.settings.SettingsUiState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.storage.CorruptionRecovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompactControlAccessibilityDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchLikeKeepsCompactVisualInsideExplicitCheckboxTarget() {
        var liked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                SearchResultCard(
                    post = samplePost(),
                    pixivUgoiraClient = null,
                    liked = liked,
                    onToggleLike = { liked = !liked },
                    onClick = {},
                )
            }
        }

        val like = composeRule.onNodeWithContentDescription("Like post")
        like.assertRole(Role.Checkbox)
        like.assertIsNotSelected()
        like.assertStateDescription("Not liked")
        like.assertTouchWidthIsEqualTo(48.dp)
        like.assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("Search like visual", useUnmergedTree = true)
            .assertWidthIsEqualTo(30.dp)
            .assertHeightIsAtLeast(30.dp)

        like.performClick()

        composeRule.onNodeWithContentDescription("Unlike post")
            .assertIsSelected()
            .assertStateDescription("Liked")
    }

    @Test
    fun forYouSourceSelectorExposesSelectedSourceAndExpandedState() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ForYouSourceSelector(
                    selectedSource = SourceKey.PIXIV,
                    availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
                    enabled = true,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onSourceSelected = {},
                )
            }
        }

        val selector = composeRule.onNodeWithContentDescription("Select For You source")
        selector.assertRole(Role.DropdownList)
        selector.assertStateDescription("Collapsed; Pixiv selected")
        selector.assertTouchHeightIsEqualTo(48.dp)
        assertTrue(selector.fetchSemanticsNode().config.contains(SemanticsActions.Expand))
        composeRule.onNodeWithTag("For You source icon", useUnmergedTree = true)
            .assertWidthIsEqualTo(18.dp)

        selector.performClick()

        composeRule.onNodeWithContentDescription("Select For You source")
            .assertStateDescription("Expanded; Pixiv selected")
        assertTrue(
            composeRule.onNodeWithContentDescription("Select For You source")
                .fetchSemanticsNode().config.contains(SemanticsActions.Collapse),
        )
    }

    @Test
    fun settingsHeaderPublishesButtonRoleAndExpansionAction() {
        var expanded by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsSection(
                        title = "Updates",
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    ) {
                        Text("Update controls")
                    }
                }
            }
        }

        val header = composeRule.onNode(hasText("Updates") and hasClickAction())
        header.assertRole(Role.Button)
        header.assertStateDescription("Expanded")
        header.assertTouchHeightIsEqualTo(48.dp)
        assertTrue(header.fetchSemanticsNode().config.contains(SemanticsActions.Collapse))
        assertFalse(header.fetchSemanticsNode().config.contains(SemanticsActions.Expand))

        header.performClick()

        val collapsed = composeRule.onNode(hasText("Updates") and hasClickAction())
        collapsed.assertStateDescription("Collapsed")
        assertTrue(collapsed.fetchSemanticsNode().config.contains(SemanticsActions.Expand))
        assertFalse(collapsed.fetchSemanticsNode().config.contains(SemanticsActions.Collapse))
    }

    @Test
    fun settingsScreenRoutesKeyedExpansionThroughItsOwnerContract() {
        var received: SettingsAction? = null
        val expansion = SettingsSectionExpansionState(
            expandedBySection = SettingsSectionKey.entries.associateWith { section ->
                section == SettingsSectionKey.UPDATES
            },
        )
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(sectionExpansion = expansion),
                    onAction = { received = it },
                )
            }
        }

        composeRule.onNode(hasText("Updates") and hasClickAction())
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(
                received == SettingsAction.SetSectionExpanded(
                    SettingsSectionKey.UPDATES,
                    expanded = false,
                ),
            )
        }
    }

    @Test
    fun settingsShowsConciseRecoveryReasonWithoutDiagnosticPathOrResetAction() {
        val fullPath = "/private/app/data/query_store.json.corrupt-7-${"a".repeat(64)}"
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        legacyJsonRecoveries = listOf(
                            CorruptionRecovery(
                                reason = "query_store.json contains malformed JSON",
                                backupPath = fullPath,
                                logicalStore = "Saved searches",
                                logicalFile = "query_store.json",
                                sha256 = "a".repeat(64),
                                byteCount = 7L,
                            )
                        )
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNode(hasText("Saved searches was reset", substring = true))
            .performScrollTo()
            .assert(hasText("unreadable local data was preserved", substring = true))
            .assert(hasText("checksum aaaaaaaa", substring = true))
        composeRule.onAllNodesWithText(fullPath).assertCountEquals(0)
        composeRule.onAllNodesWithText("Reset recovered storage").assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertRole(expected: Role) =
        assert(expectValue(SemanticsProperties.Role, expected))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(
        expected: String,
    ) = assert(expectValue(SemanticsProperties.StateDescription, expected))

    private fun samplePost(): Post {
        return Post(
            id = PostId(SourceKey.GELBOORU, "accessibility-card"),
            preview = ImageRef(url = null, localPath = null, mime = "image/jpeg"),
            full = null,
            pageUrl = null,
            width = 800,
            height = 1_000,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
