package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.testing.waitUntilSettled
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerSaveButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `hiding and showing the dock keeps the suggested Codex instead of reloading it`() {
        val docked = mutableStateOf(true)
        val fan = ViewerSaveFanState()
        val quickSave = SuggestsCatsOnce()
        compose.setContent {
            CompositionLocalProvider(LocalQuickSave provides quickSave) {
                if (docked.value) ViewerSaveButton(POST, ViewerSaveContext(), fan, onInteraction = {}, onOpenPicker = {})
            }
        }
        compose.waitUntilSettled { compose.onAllNodesWithContentDescription("Save to Cats").fetchSemanticsNodes().isNotEmpty() }
        docked.value = false
        compose.waitForIdle()
        docked.value = true
        compose.onNodeWithContentDescription("Save to Cats").assertExists()
    }

    @Test fun `holding Save and releasing on a fanned Codex saves there`() {
        val fan = ViewerSaveFanState()
        val quickSave = SuggestsCatsOnce(SaveSuggestion("dogs", "Dogs", emptyList()))
        compose.setContent {
            CompositionLocalProvider(LocalQuickSave provides quickSave) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        ViewerSaveButton(POST, ViewerSaveContext(), fan, onInteraction = {}, onOpenPicker = {})
                    }
                    ViewerSaveFanOverlay(fan)
                }
            }
        }
        compose.waitUntilSettled { compose.onAllNodesWithContentDescription("Save to Cats").fetchSemanticsNodes().isNotEmpty() }
        val button = compose.onNodeWithTag("viewer-dock-save").getBoundsInRoot()
        compose.onNodeWithTag("viewer-dock-save").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            move()
        }
        val dogs = compose.onNodeWithTag("Collect target codex:dogs").getBoundsInRoot()
        compose.onNodeWithTag("viewer-dock-save").performTouchInput {
            val toDogs = with(density) { Offset((dogs.left + dogs.right - button.left * 2).toPx() / 2, (dogs.top + dogs.bottom - button.top * 2).toPx() / 2) }
            moveTo(toDogs)
        }
        compose.onNodeWithTag("viewer-dock-save").performTouchInput { up() }
        compose.waitUntilSettled { quickSave.saved == "dogs" }
    }

    private class SuggestsCatsOnce(private vararg val more: SaveSuggestion) : QuickSave {
        private var scored = false
        var saved: String? = null
        override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow(null)
        override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? {
            saved = codexId
            return null
        }
        override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
        override suspend fun undoQuickSave(record: QuickSaveRecord) = Unit
        override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flow {
            if (scored) awaitCancellation()
            scored = true
            emit(SaveSuggestions(listOf(SaveSuggestion("cats", "Cats", emptyList())) + more))
        }
    }

    private companion object {
        val IMAGE = ImageRef(url = "https://example.test/a.jpg", localPath = null, mime = "image/jpeg")
        val POST = Post(
            id = PostId(SourceKey.IWARA, "a"), preview = IMAGE, full = IMAGE, pageUrl = null,
            width = 1000, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
    }
}
