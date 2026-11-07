package com.theoriacodex.app.settings.sites

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.domain.source.ProtocolFamily
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class CustomSitesScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(controller: FakeCustomSitesController) {
        val owner = CustomSitesViewModel(controller)
        compose.setContent {
            val state by owner.state.collectAsStateWithLifecycle()
            MaterialTheme { CustomSitesScreen(state, owner::onAction, onBack = {}) }
        }
    }

    @Test
    fun `adding a site probes it, confirms its name and lists it`() {
        val controller = FakeCustomSitesController().apply {
            onProbe = { supported("danbooru.example", ProtocolFamily.DANBOORU, label = "Example Booru") }
        }
        show(controller)
        compose.onNodeWithText("Add a booru by its address", substring = true).assertExists()
        compose.onNode(hasText("Add site") and hasClickAction()).performClick()

        compose.onNodeWithText("Site address").performTextInput("https://danbooru.example/posts?tags=cat")
        compose.onNodeWithText("Check site").performClick()

        compose.onNodeWithText("Found a Danbooru site. Name it and add it.").assertExists()
        compose.onNodeWithText("Example Booru").assertExists()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNode(hasText("Add site") and hasClickAction()).performClick()

        compose.onNodeWithText("Example Booru").assertExists()
        compose.onNodeWithText("danbooru.example · Danbooru · Ready").assertExists()
        assertEquals("https://danbooru.example/posts?tags=cat", controller.probes.single().input)
    }

    @Test
    fun `a site needing keys asks for a masked API key`() {
        val controller = FakeCustomSitesController(
            listOf(site(availability = CustomSiteAvailability.NEEDS_CREDENTIALS)),
        )
        show(controller)
        compose.onNodeWithText("safebooru.org · Gelbooru 0.2 · Needs API key").assertExists()
        compose.onNodeWithContentDescription("Re-test Safebooru").assertExists()
        compose.onNodeWithText("Safebooru").performClick()
        compose.onNodeWithText("User ID").assertExists()
        compose.onNode(hasText("API key") and hasSetTextAction()).performTextInput("secret")
        compose.onNodeWithText("••••••").assertExists()
    }

    @Test
    fun `removing a site explains that library data stays`() {
        val controller = FakeCustomSitesController(listOf(site()))
        show(controller)
        compose.onNodeWithText("Safebooru").performClick()
        compose.onNodeWithText("Remove site").performClick()

        compose.onNodeWithText(removeConfirmationText("Safebooru")).assertExists()
        compose.onNodeWithText(
            "Likes, Codex items and history from this site stay in your library",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("Remove").performClick()

        compose.onNodeWithText("Safebooru").assertDoesNotExist()
        assertEquals(listOf("custom_safebooru_org"), controller.removals)
    }
}
