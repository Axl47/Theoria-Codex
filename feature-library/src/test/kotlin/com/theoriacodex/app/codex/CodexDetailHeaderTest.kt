package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h915dp")
class CodexDetailHeaderTest {
    @get:Rule val compose = createComposeRule()
    private val renames = mutableListOf<String>()
    private lateinit var focus: FocusManager

    private val header = @androidx.compose.runtime.Composable {
        focus = LocalFocusManager.current
        MaterialTheme {
            CodexEditableHeader(
                "c1", "Prints", emptyList(), emptyList(), false, CodexEditSelection(),
                CodexHeaderActions(onRename = { renames += it }, onOpenActions = null, onTopUpOffline = null),
                {}, {}, {}, {},
            )
        }
    }

    @Test fun `leaving the field commits a changed name once`() {
        compose.setContent(header)
        compose.onNodeWithText("Prints").performClick()
        compose.onNodeWithTag("codex-name-field").performTextReplacement("Etchings")
        compose.runOnIdle { focus.clearFocus() }
        compose.runOnIdle { assertEquals(listOf("Etchings"), renames) }
    }

    @Test fun `the typed name survives recreation without renaming`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent(header)
        compose.onNodeWithText("Prints").performClick()
        compose.onNodeWithTag("codex-name-field").performTextReplacement("Etch")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("codex-name-field").assertTextEquals("Etch")
        compose.runOnIdle { assertEquals(emptyList<String>(), renames) }
    }
}
