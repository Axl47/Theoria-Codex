package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationStateBoundarySubscriptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `independent boundary subscriptions recompose only their consuming child`() {
        val settingsState = mutableStateOf("settings-0")
        val recentsState = mutableStateOf("recents-0")
        val codexState = mutableStateOf("codex-0")
        val credentialState = mutableStateOf("credentials-0")
        var shellCompositions = 0
        val destinationCompositions = initialDestinationCompositions()
        val renderedValues = linkedMapOf<String, String>()

        setBoundaryContent(settingsState, recentsState, codexState, credentialState,
            destinationCompositions, renderedValues) { shellCompositions += 1 }
        composeRule.waitForIdle()
        val settledShellCompositions = shellCompositions
        val settledDestinationCompositions = destinationCompositions.toMap()

        composeRule.runOnIdle { settingsState.value = "settings-1" }
        composeRule.waitForIdle()
        assertDestinationsAdvanced(
            initialShell = settledShellCompositions, currentShell = shellCompositions,
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings"),
        )
        assertEquals("settings-1", renderedValues["settings"])

        composeRule.runOnIdle { recentsState.value = "recents-1" }
        composeRule.waitForIdle()
        assertDestinationsAdvanced(
            initialShell = settledShellCompositions, currentShell = shellCompositions,
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents"),
        )
        composeRule.runOnIdle { codexState.value = "codex-1" }
        composeRule.waitForIdle()
        assertDestinationsAdvanced(
            initialShell = settledShellCompositions, currentShell = shellCompositions,
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents", "codex"),
        )
        composeRule.runOnIdle { credentialState.value = "credentials-1" }
        composeRule.waitForIdle()

        assertDestinationsAdvanced(
            initialShell = settledShellCompositions, currentShell = shellCompositions,
            initial = settledDestinationCompositions,
            current = destinationCompositions,
            advanced = setOf("settings", "recents", "codex", "credentials"),
        )
    }

    private fun assertDestinationsAdvanced(
        initialShell: Int, currentShell: Int,
        initial: Map<String, Int>,
        current: Map<String, Int>,
        advanced: Set<String>,
    ) {
        assertEquals(initialShell, currentShell)
        assertEquals(
            initial.mapValues { (name, count) -> count + if (name in advanced) 1 else 0 },
            current,
        )
    }

    private fun MutableMap<String, Int>.increment(name: String) {
        this[name] = getValue(name) + 1
    }

    private fun initialDestinationCompositions() = linkedMapOf(
        "settings" to 0,
        "recents" to 0,
        "codex" to 0,
        "credentials" to 0,
    )

    private fun setBoundaryContent(
        settingsState: androidx.compose.runtime.State<String>,
        recentsState: androidx.compose.runtime.State<String>,
        codexState: androidx.compose.runtime.State<String>,
        credentialState: androidx.compose.runtime.State<String>,
        destinationCompositions: MutableMap<String, Int>,
        renderedValues: MutableMap<String, String>,
        onShellComposition: () -> Unit,
    ) {
        composeRule.setContent {
            onShellComposition()
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
    }


}
