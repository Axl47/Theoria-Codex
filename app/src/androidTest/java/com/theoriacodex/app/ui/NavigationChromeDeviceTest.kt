package com.theoriacodex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.codex.CodexDetailScreen
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationChromeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun filterFabExposesCurrentStateAndDispatchesClicks() {
        var active by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme { FeedFilterFab(active, "Filters", { clicks++ }) }
        }
        val fab = compose.onNodeWithContentDescription("Filters")
        fab.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No active filters"))
        compose.runOnIdle { active = true }
        fab.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Filters active"))
        fab.performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun filteredCodexCardLaunchesOnlyVisiblePostsInRepositoryOrder() {
        val first = post(SourceKey.PIXIV, "first")
        val hidden = post(SourceKey.GELBOORU, "hidden")
        val second = post(SourceKey.PIXIV, "second")
        var launch: Pair<List<Post>, Int>? = null
        compose.setContent {
            MaterialTheme {
                CodexDetailScreen(
                    codexName = "Collection", posts = listOf(first, hidden, second),
                    sortMode = CodexSortMode.OLDEST_SAVED,
                    availableSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
                    creatorBrowsingSources = emptySet(),
                    fabRestoreState = FeedFabRestoreState(source = SourceKey.PIXIV.name),
                    onOpenViewer = { posts, index -> launch = posts to index },
                    onAddPostsToAnotherCodex = {}, onRemovePosts = {}, onSavePostToDevice = {},
                    onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {},
                    onBack = {}, onDeleteCodex = {}, isLikesCodex = false,
                )
            }
        }
        compose.onNodeWithTag(searchCardTestTag(hidden.id)).assertDoesNotExist()
        compose.onNodeWithTag(searchCardTestTag(second.id)).performClick()
        compose.runOnIdle { assertEquals(listOf(first, second) to 1, launch) }
    }

    private fun post(source: SourceKey, id: String) = Post(
        id = PostId(source, id), preview = ImageRef(null, null, "image/jpeg"), full = null,
        pageUrl = null, width = 200, height = 200, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
