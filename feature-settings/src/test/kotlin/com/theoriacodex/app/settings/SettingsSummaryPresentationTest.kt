package com.theoriacodex.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSummaryPresentationTest {
    @Test
    fun `account summary counts configured providers without exposing credentials`() {
        val accounts = SettingsAccountUiState(
            pixivConnected = true,
            gelbooruStatusLabel = "Configured",
            gelbooruConfigured = true,
            rule34XxxStatusLabel = "Not configured",
            gelbooruUserIdInput = "42",
            gelbooruApiKeyInput = "secret",
        )

        assertEquals("2 of 3 accounts", sourceAccountsSummary(accounts))
    }

    @Test
    fun `cache summary combines both visible cache categories`() {
        assertEquals("0 cached items", cacheSummary(0, 0))
        assertEquals("1 cached item", cacheSummary(1, 0))
        assertEquals("5 cached items", cacheSummary(2, 3))
    }
}
