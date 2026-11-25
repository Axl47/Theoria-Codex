package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
@Config(application = Application::class)
class SavedSearchEditingEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `editing a saved search opens the composer and closing it ends the edit without saving (ADR 0218)`() {
        val evening = SavedSearchEntry("evening", "Evening", RecentSearchEntry(
            Query(QueryMode.Unified, listOf("night"), emptyList(), SortMode.NEWEST, null, null), "night", 1,
        ), 1)
        val composerOpen = mutableStateOf(false)
        var editing by mutableStateOf<SavedSearchEntry?>(null)
        val ended = mutableListOf<SavedSearchEntry>()
        compose.setContent {
            SavedSearchEditingEffect(editing, composerOpen) { ended += it; editing = null }
        }
        compose.runOnIdle { editing = evening }
        compose.runOnIdle {
            assertEquals(true, composerOpen.value)
            assertEquals(emptyList<SavedSearchEntry>(), ended)
            composerOpen.value = false
        }
        compose.runOnIdle {
            assertEquals(listOf(evening), ended)
            assertEquals(false, composerOpen.value)
        }
    }
}
