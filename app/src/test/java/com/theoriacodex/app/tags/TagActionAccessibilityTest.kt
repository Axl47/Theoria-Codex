package com.theoriacodex.app.tags

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TagActionAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `narrow tag actions expose contextual names selected state button role and 48dp geometry`() {
        var density = 1f
        val removed = mutableListOf<SearchTerm>()
        val tag = SearchTerm("portrait")
        compose.setContent {
            val scale = LocalDensity.current.density
            SideEffect { density = scale }
            MaterialTheme { Box(Modifier.width(320.dp)) {
                PostTagActionSection(testPost().copy(canonicalTags = listOf("portrait", "second", "third"), taxonomy = emptyList()),
                    draftQuery = emptySearchQuery().withIncludeTermsAsRequired(listOf(tag)),
                    onAddIncludeTerm = { true }, onAddExcludeTerm = { true },
                    onRemoveIncludeTerm = { removed += it }, onRemoveExcludeTerm = {})
            } }
        }
        val action = compose.onNodeWithContentDescription("Include portrait in Search")
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f * density - 1f)
        assertTrue(bounds.height >= 48f * density - 1f)
        action.performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf(tag), removed)
    }

    @Test fun `favorite actions name the tag and expose an accessibility click`() {
        val removed = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            FavoriteTagActionGrid(SourceKey.PIXIV, listOf("portrait", "second", "third"),
                onAddTag = {}, onRemoveTag = { removed += it })
        } } }
        compose.onNodeWithContentDescription("Remove portrait from favorites")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("portrait"), removed)
    }
}
