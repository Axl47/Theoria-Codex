package com.theoriacodex.app.tags

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PostTagFilterTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a long list shows the filter, which narrows the tags past their fold`() {
        setContent(sheetExtras = null)

        compose.onNode(hasSetTextAction()).performTextInput("tag 29")
        compose.onNodeWithText("tag_299").assertExists()
        compose.onNodeWithText("tag_0").assertDoesNotExist()
        compose.onNodeWithText("more", substring = true).assertDoesNotExist()

        compose.onNodeWithContentDescription("Clear tag filter").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        compose.onNodeWithText("No tags match \"zzz\"").assertExists()
    }

    @Test
    fun `a sheet short on height keeps the filter behind the header's button`() {
        setContent(PostTagSheetExtras(tagsInitiallyCollapsed = true, filterBehindIcon = true))

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Filter tags").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("tag 42")
        compose.onNodeWithText("tag_42").assertExists()
        compose.onNodeWithText("tag_0").assertDoesNotExist()

        compose.onNodeWithContentDescription("Close tag filter").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("tag_0").assertExists()
    }

    private fun setContent(sheetExtras: PostTagSheetExtras?) {
        val post = Post(
            id = PostId(SourceKey.GELBOORU, "1"),
            preview = ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
            full = null,
            pageUrl = "https://example.test/post/1",
            width = null,
            height = null,
            canonicalTags = List(300) { "tag_$it" },
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    PostTagActionSection(
                        post = post,
                        draftQuery = emptySearchQuery(),
                        onAddIncludeTerm = { true },
                        onAddExcludeTerm = { true },
                        onRemoveIncludeTerm = {},
                        onRemoveExcludeTerm = {},
                        sheetExtras = sheetExtras,
                    )
                }
            }
        }
    }
}
