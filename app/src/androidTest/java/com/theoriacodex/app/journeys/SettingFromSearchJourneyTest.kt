package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.theoriacodex.data.repository.SettingToggles
import kotlinx.coroutines.flow.first
import org.junit.Test

class SettingFromSearchJourneyTest : AppJourneyFixture() {
    @Test
    fun aSettingSwitchedFromTheSearchBoxShowsItsNewValueInSettings() {
        val toggle = SettingToggles.InstantSeek
        launch()
        activate(compose.onNodeWithTag("Search bar"))
        val input = compose.onNodeWithTag("Search query input")
        activate(input)
        input.performTextInput("instant seek")
        activate(compose.onNode(hasText(toggle.title) and isToggleable() and hasAnyAncestor(hasTestTag("Search composer"))))
        waitFor("setting ${toggle.title} is on in settings") { io { toggle.read(container.settings.observeSettings().first()) } }

        val composer = compose.onNodeWithTag("Search composer")
        repeat(2) { if (composer.isDisplayed()) pressSystemBack().also { compose.waitForIdle() } }
        waitFor("search composer is closed") { !composer.isDisplayed() }
        openSettings()
        activate(compose.onNodeWithText("Controls"))
        compose.onNode(hasText(toggle.title) and isToggleable()).assertIsOn()
    }
}
