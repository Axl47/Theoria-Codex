package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DestinationStateBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings recents codex and credential emissions stay below shell`() {
        val settingsState = mutableStateOf("settings-0")
        val recentsState = mutableStateOf("recents-0")
        val codexState = mutableStateOf("codex-0")
        val credentialState = mutableStateOf("credentials-0")
        var shellCompositions = 0
        val destinationCompositions = linkedMapOf(
            "settings" to 0,
            "recents" to 0,
            "codex" to 0,
            "credentials" to 0,
        )
        val renderedValues = linkedMapOf<String, String>()

        composeRule.setContent {
            shellCompositions += 1
            DestinationStateBoundary(settingsState) { value ->
                destinationCompositions.increment("settings")
                renderedValues["settings"] = value
            }
            DestinationStateBoundary(recentsState) { value ->
                destinationCompositions.increment("recents")
                renderedValues["recents"] = value
            }
            DestinationStateBoundary(codexState) { value ->
                destinationCompositions.increment("codex")
                renderedValues["codex"] = value
            }
            DestinationStateBoundary(credentialState) { value ->
                destinationCompositions.increment("credentials")
                renderedValues["credentials"] = value
            }
        }
        composeRule.waitForIdle()
        val settledShellCompositions = shellCompositions
        val settledDestinationCompositions = destinationCompositions.toMap()

        composeRule.runOnIdle { settingsState.value = "settings-1" }
        composeRule.waitForIdle()
        assertEquals(settledShellCompositions, shellCompositions)
        assertDestinationsAdvanced(
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings"),
        )
        assertEquals("settings-1", renderedValues["settings"])

        composeRule.runOnIdle { recentsState.value = "recents-1" }
        composeRule.waitForIdle()
        assertEquals(settledShellCompositions, shellCompositions)
        assertDestinationsAdvanced(
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents"),
        )
        composeRule.runOnIdle { codexState.value = "codex-1" }
        composeRule.waitForIdle()
        assertEquals(settledShellCompositions, shellCompositions)
        assertDestinationsAdvanced(
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents", "codex"),
        )
        composeRule.runOnIdle { credentialState.value = "credentials-1" }
        composeRule.waitForIdle()

        assertEquals(settledShellCompositions, shellCompositions)
        assertDestinationsAdvanced(
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents", "codex", "credentials"),
        )
    }

    private fun assertDestinationsAdvanced(
        initial: Map<String, Int>,
        current: Map<String, Int>,
        advanced: Set<String>,
    ) {
        assertEquals(
            initial.mapValues { (name, count) -> count + if (name in advanced) 1 else 0 },
            current,
        )
    }

    private fun MutableMap<String, Int>.increment(name: String) {
        this[name] = getValue(name) + 1
    }
}
