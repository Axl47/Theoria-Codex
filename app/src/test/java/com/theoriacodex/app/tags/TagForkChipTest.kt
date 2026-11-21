package com.theoriacodex.app.tags

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagForkChipTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `where tags fork, a tap forks and holding offers the Search moves`() {
        val forked = mutableListOf<String>()
        val included = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                PostTagActionSection(
                    post = post(),
                    draftQuery = emptySearchQuery(),
                    onAddIncludeTerm = { term -> included += term.value; true },
                    onAddExcludeTerm = { true },
                    onRemoveIncludeTerm = {},
                    onRemoveExcludeTerm = {},
                    onFavoriteTagLongPress = null,
                    sheetExtras = PostTagSheetExtras(tagsInitiallyCollapsed = false, onFork = { term: SearchTerm -> forked += term.value }),
                )
            }
        }

        compose.onNodeWithText("cat").performClick()
        assertEquals(listOf("cat") to emptyList<String>(), forked to included)

        compose.onNodeWithText("cat").performTouchInput { longClick() }
        compose.onNodeWithText("Include cat in Search").performClick()
        assertEquals(listOf("cat") to listOf("cat"), forked to included)
    }

    private fun post() = Post(
        id = PostId(SourceKey.GELBOORU, "1"),
        preview = ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = listOf("cat"),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
