package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SavedSearchesCodexFiltersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `include exclude all and named search management retain stable pin identities`() {
        val entries = mutableStateOf(listOf(entry("a", "First"), entry("b", "Second")))
        val state = mutableStateOf(FeedFabRestoreState())
        var renamed: Pair<String, String>? = null
        var removed: String? = null
        compose.setContent { MaterialTheme { Column {
            SavedSearchesCodexFilters(entries.value, state.value, { state.value = it },
                { id, name -> renamed = id to name }, { removed = it })
        } } }
        compose.onNodeWithContentDescription("Include First").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Include First").assertIsOff()
        compose.onNodeWithContentDescription("Include Second").assertIsOn()
        compose.onNodeWithText("Exclude all").performClick()
        compose.runOnIdle { assertEquals(setOf("a", "b"), state.value.excludedSavedSearches.toSet()) }
        compose.onNodeWithText("Include all").performClick()
        compose.onNodeWithContentDescription("Include Second").assertIsOn()
        compose.onNodeWithText("Find saved search").performTextReplacement("Second")
        compose.onNodeWithContentDescription("Include First").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rename Second").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Renamed")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("b" to "Renamed", renamed) }
        compose.onNodeWithContentDescription("Remove Second").performClick()
        compose.runOnIdle { assertEquals("b", removed) }
    }

    private fun entry(id: String, name: String) = SavedSearchEntry(id, name, RecentSearchEntry(
        Query(QueryMode.Unified, listOf("tag"), emptyList(), SortMode.NEWEST, null, null), id, 1,
    ), 1)
}
