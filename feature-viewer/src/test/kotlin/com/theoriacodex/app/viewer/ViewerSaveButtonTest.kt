package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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

    /** Scores the post once; a later load never finishes, as if scoring were still walking the library. */
    private class SuggestsCatsOnce : QuickSave {
        private var scored = false
        override val lastQuickSave: StateFlow<QuickSaveRecord?> = MutableStateFlow(null)
        override suspend fun quickSave(post: Post, codexId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
        override suspend fun saveToNew(post: Post, name: String, profileId: String, query: Query?, fromForYou: Boolean): QuickSaveRecord? = null
        override suspend fun undoQuickSave(record: QuickSaveRecord) = Unit
        override fun suggestions(post: Post, query: Query?, limit: Int): Flow<SaveSuggestions> = flow {
            if (scored) awaitCancellation()
            scored = true
            emit(SaveSuggestions(listOf(SaveSuggestion("cats", "Cats", emptyList())), inAnyCodex = false))
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
