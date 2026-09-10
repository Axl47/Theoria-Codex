package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.ScenarioPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryPresentationTest {
    @Test
    fun `account summary counts configured providers without exposing credentials`() {
        val accounts = SettingsAccountUiState(
            pixivConnected = true,
            gelbooruStatusLabel = "Configured",
            rule34XxxStatusLabel = "Not configured",
            gelbooruUserIdInput = "42",
            gelbooruApiKeyInput = "secret",
        )

        assertEquals("2 of 3 configured", sourceAccountsSummary(accounts))
    }

    @Test
    fun `cache summary combines both visible cache categories`() {
        assertEquals("0 cached items", cacheSummary(0, 0))
        assertEquals("1 cached item", cacheSummary(1, 0))
        assertEquals("5 cached items", cacheSummary(2, 3))
    }

    @Test
    fun `developer scenario summary uses compact user facing text`() {
        assertEquals("Normal", scenarioLabel(ScenarioPreset.NORMAL))
        assertEquals("Partial failure", scenarioLabel(ScenarioPreset.PARTIAL_FAILURE))
        assertEquals("Empty results", scenarioLabel(ScenarioPreset.EMPTY_RESULTS))
        assertEquals("Slow network", scenarioLabel(ScenarioPreset.SLOW_NETWORK))
    }

    @Test
    fun `Codex entry count uses singular and plural labels`() {
        assertEquals("Entered 1 Time", codexEntryCountLabel(1))
        assertEquals("Entered 2 Times", codexEntryCountLabel(2))
    }
}
