package com.theoriacodex.app.creator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountBioTest {
    @get:Rule val compose = createComposeRule()

    private val bio = (1..8).joinToString("\n") { "Bio line $it" }

    private fun bioHeight() = compose.onNodeWithText(bio).fetchSemanticsNode().size.height

    @Test
    fun `an expanded bio stays expanded when the profile is composed again from saved state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { AccountBio(bio) } }
        val collapsed = bioHeight()
        compose.onNodeWithText(bio).performClick()
        compose.waitForIdle()
        val expanded = bioHeight()
        assertTrue("expanding should show more lines ($collapsed -> $expanded)", expanded > collapsed)

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(expanded, bioHeight())
    }
}
