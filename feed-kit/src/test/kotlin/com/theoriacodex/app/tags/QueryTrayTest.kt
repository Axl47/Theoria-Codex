package com.theoriacodex.app.tags

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h360dp-land")
class QueryTrayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `in landscape the tray starts folded to its terms beside Search, and unfolds to edit groups`() {
        var searched = false
        val query = Query(QueryMode.Unified, listOf(SearchTermGroup(listOf(SearchTerm("rain"), SearchTerm("neon")))), emptyList(), SortMode.NEWEST, null, null)
        compose.setContent {
            MaterialTheme {
                QueryTray("Your search", "Tap a tag to start a search.", query, targetGroup = null, onTargetGroup = {}, onSearch = { searched = true })
            }
        }
        compose.onNodeWithText("rain", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Your search").assertDoesNotExist()
        compose.onNodeWithText("+ or").assertDoesNotExist()
        compose.onNodeWithText("Search").performClick()
        compose.runOnIdle { assertTrue(searched) }
        compose.onNodeWithText("rain", substring = true).performClick()
        compose.onNodeWithText("Your search").assertExists()
        compose.onNodeWithText("+ or").assertExists()
    }
}
