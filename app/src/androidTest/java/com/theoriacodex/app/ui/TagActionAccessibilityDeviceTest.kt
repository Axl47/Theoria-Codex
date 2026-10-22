package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.tags.FavoriteTagActionGrid
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual Android semantics and narrow geometry; it does not change the user's accessibility services. */
class TagActionAccessibilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedTagRemovesTheCanonicalDraftTermThroughItsAccessibilityAction() {
        val term = SearchTerm("portrait")
        var draft by mutableStateOf(emptySearchQuery().withIncludeTermsAsRequired(listOf(term)))
        val post = Post(PostId(SourceKey.PIXIV, "accessibility-fixture"), ImageRef(null, null, null), null,
            pageUrl = null, width = null, height = null, canonicalTags = listOf("portrait", "second", "third"),
            rawTags = emptyList(), authorName = null, createdAtEpochMs = null)
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            PostTagActionSection(post, draftQuery = draft, onAddIncludeTerm = { true }, onAddExcludeTerm = { true },
                onRemoveIncludeTerm = { removed -> draft = draft.withIncludeTermsAsRequired(draft.includeTerms - removed) },
                onRemoveExcludeTerm = {})
        } } }
        val action = compose.onNodeWithContentDescription("Include portrait in Search")
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        action.assertWidthIsAtLeast(48.dp).assertHeightIsEqualTo(24.dp)
        action.performSemanticsAction(SemanticsActions.OnClick) { it() }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.runOnIdle { assertEquals(emptyList<SearchTerm>(), draft.includeTerms) }
    }

    @Test fun favoriteRemovalNamesTheTagAndWorksThroughAccessibility() {
        val removed = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            FavoriteTagActionGrid(SourceKey.PIXIV, listOf("portrait", "second", "third"),
                onAddTag = {}, onRemoveTag = { removed += it })
        } } }
        compose.onNodeWithContentDescription("Remove portrait from favorites")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(listOf("portrait"), removed) }
    }
}
