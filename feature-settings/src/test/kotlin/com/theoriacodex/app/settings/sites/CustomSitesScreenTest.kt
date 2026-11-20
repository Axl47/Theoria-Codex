package com.theoriacodex.app.settings.sites

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.customsites.BuiltInSiteSummary
import com.theoriacodex.app.customsites.BuiltInTestResult
import com.theoriacodex.app.customsites.CustomSiteAvailability
import com.theoriacodex.app.settings.BuiltInAccounts
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProbeFailure
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteProbeResult
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

    private fun show(controller: FakeCustomSitesController, accountControls: @Composable (SourceKey) -> Unit = {}) {
        val owner = CustomSitesViewModel(controller)
        val accounts = BuiltInAccounts(summary = { null }, controls = accountControls)
        compose.setContent {
            val state by owner.state.collectAsStateWithLifecycle()
            MaterialTheme { CustomSitesScreen(state, accounts, owner::onAction, onBack = {}) }
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
        compose.onNodeWithText("Remove site").performScrollTo().performClick()

        compose.onNodeWithText(removeConfirmationText("Safebooru")).assertExists()
        compose.onNodeWithText(
            "Likes, Codex items and history from this site stay in your library",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("Remove").performClick()

        compose.onNodeWithText("Safebooru").assertDoesNotExist()
        assertEquals(listOf("custom_safebooru_org"), controller.removals)
    }

    @Test
    fun `a re-test blocked by a challenge offers browser verification`() {
        val controller = FakeCustomSitesController(listOf(site())).apply {
            onRetest = { SiteProbeResult.Unreachable(address("safebooru.org"), ProbeFailure.BLOCKED) }
        }
        show(controller)
        compose.onNodeWithText("Verify in browser").assertDoesNotExist()

        compose.onNodeWithContentDescription("Re-test Safebooru").performClick()

        compose.onNodeWithText(unreachableMessage("safebooru.org", ProbeFailure.BLOCKED)).assertExists()
        compose.onNodeWithText("Verify in browser").assertExists()
    }

    @Test
    fun `a site proven to work shows the check mark on its row and Browser check row`() {
        val controller = FakeCustomSitesController(listOf(site()))
        controller.workingSourceIds.value = setOf("custom_safebooru_org")
        show(controller)
        compose.onNodeWithContentDescription("Working").assertExists()
        compose.onNodeWithText("Working").assertDoesNotExist()

        compose.onNodeWithText("Safebooru").performClick()

        compose.onNodeWithContentDescription("Working").assertExists()
    }

    @Test
    fun `saved keys sit behind the API key item, which opens to show their options`() {
        show(FakeCustomSitesController(listOf(site().copy(hasCredentials = true))))
        compose.onNodeWithText("Safebooru").performClick()
        compose.onNode(hasText("API key") and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Remove saved keys").assertDoesNotExist()

        compose.onNode(hasText("API key") and hasClickAction()).performScrollTo().performClick()

        compose.onNode(hasText("API key") and hasSetTextAction()).assertExists()
        compose.onNodeWithText("Remove saved keys").assertExists()
    }

    @Test
    fun `Test all reports on the status line and offers verification on a challenged row`() {
        val iwara = BuiltInSiteSummary(SourceKey.IWARA, "Iwara", "www.iwara.tv", working = false, needsVerification = false)
        val controller = FakeCustomSitesController().apply {
            builtInSites.value = listOf(iwara)
            onTestBuiltIn = { BuiltInTestResult.NEEDS_BROWSER_CHECK }
        }
        show(controller)

        compose.onNodeWithText("Test all").performClick()

        compose.onNodeWithText("0 of 1 working").assertExists()
        compose.onNodeWithText("Needs browser verification.").assertExists()
        compose.onNodeWithText("Verify in browser").assertExists()
    }

    @Test
    fun `a built-in source opens its own page with the full path, browser check and account`() {
        val iwara = BuiltInSiteSummary(SourceKey.IWARA, "Iwara", "www.iwara.tv", working = false, needsVerification = true)
        val controller = FakeCustomSitesController().apply { builtInSites.value = listOf(iwara) }
        show(controller, accountControls = { Text("Account for ${it.id}") })
        compose.onNodeWithText("Settings / Sites").assertExists()
        compose.onNodeWithText("www.iwara.tv · Needs browser verification").assertExists()

        compose.onNodeWithText("Iwara").performClick()

        compose.onNodeWithText("Settings / Sites / Iwara").assertExists()
        compose.onNodeWithText("Verify in browser").assertExists()
        compose.onNodeWithText("Account for IWARA").assertExists()
    }

    @Test
    fun `the edit page has a Browser check row`() {
        show(FakeCustomSitesController(listOf(site())))
        compose.onNodeWithText("Safebooru").performClick()

        compose.onNodeWithText("Browser check").assertExists()
        compose.onNodeWithText("Verify in browser").assertExists()
        compose.onNodeWithText("Open this site to pass its browser check").assertExists()
    }
}
