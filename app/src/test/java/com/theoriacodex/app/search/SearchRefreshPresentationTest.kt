package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchExecutionUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchRefreshPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `refresh keeps the existing card node attached until replacement results arrive`() {
        val post = testPost(SourceKey.PIXIV, "refresh").copy(
            preview = ImageRef(null, "/nonexistent/preview.jpg", "image/jpeg"), full = null, media = emptyList(),
        )
        val state = mutableStateOf(SearchUiState(content = SearchContentUiState(results = listOf(post), hasExecutedSearch = true)))
        compose.setContent {
            MaterialTheme { Box(Modifier.size(400.dp, 700.dp)) {
                SearchScreen(
                    state = state.value, creatorBrowsingSources = emptySet(), onAction = {},
                    resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                    tagVideoCountProvider = { _, _ -> null }, fetchTagVideoCounts = { _, _ -> emptyMap() },
                    onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {}, onRequestSaveToCodex = {}, onSaveToDevice = {},
                )
            } }
        }
        val node = compose.onNodeWithTag(searchCardTestTag(post.id))
        node.assertIsDisplayed()
        val id = node.fetchSemanticsNode().id
        compose.runOnIdle { state.value = state.value.copy(execution = SearchExecutionUiState(
            activeRequestId = 1, activeKind = SearchRequestKind.REPLACE, submittedQuery = state.value.query.applied,
        )) }
        node.assertIsDisplayed()
        assertEquals(id, node.fetchSemanticsNode().id)
        compose.runOnIdle { state.value = state.value.copy(execution = SearchExecutionUiState()) }
        assertEquals(id, node.fetchSemanticsNode().id)
    }
}
