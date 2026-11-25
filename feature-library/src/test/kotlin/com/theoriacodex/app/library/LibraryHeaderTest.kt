package com.theoriacodex.app.library

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.domain.series.ExtensionAlerts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the Library settings button badges extension alerts like Series (ADR 0221)`() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                LibraryHeader(
                    LibrarySection.Codices, onSelect = {}, onOpenSettings = { opened++ }, marked = emptySet(),
                    extensionAlerts = ExtensionAlerts(missing = 1, updates = 2), actions = {},
                )
            }
        }

        val button = compose.onNodeWithTag(LIBRARY_SETTINGS_TAG)
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 series needs its extension, 2 updates"))
        compose.onNodeWithText("3").assertIsDisplayed()
        button.performClick()
        assertEquals(1, opened)
    }
}
