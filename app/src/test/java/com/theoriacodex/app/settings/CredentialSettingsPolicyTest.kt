package com.theoriacodex.app.settings

import com.theoriacodex.sources.credentials.GelbooruCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialSettingsPolicyTest {
    @Test
    fun `configured credential presentation never exposes stored secret`() {
        val storedSecret = "stored-secret-must-not-reach-ui"
        val presentation = credentialAccountPresentation(
            credential = GelbooruCredentials(userId = "42", apiKey = storedSecret),
            userId = GelbooruCredentials::userId,
        )

        assertEquals("42", presentation.userIdInput)
        assertEquals("", presentation.apiKeyInput)
        assertEquals("Configured", presentation.statusLabel)
        assertFalse(presentation.toString().contains(storedSecret))
    }

    @Test
    fun `blank replacement keeps the configured key`() {
        assertEquals(
            ResolvedReplaceOnlyCredential(userId = "42", apiKey = "stored-key"),
            resolveReplaceOnlyCredential(
                userIdInput = " 42 ",
                replacementApiKeyInput = "",
                configuredApiKey = "stored-key",
            ),
        )
    }

    @Test
    fun `nonblank replacement supersedes the configured key`() {
        assertEquals(
            ResolvedReplaceOnlyCredential(userId = "42", apiKey = "replacement"),
            resolveReplaceOnlyCredential(
                userIdInput = "42",
                replacementApiKeyInput = " replacement ",
                configuredApiKey = "stored-key",
            ),
        )
    }

    @Test
    fun `blank replacement without configured key fails closed`() {
        assertNull(
            resolveReplaceOnlyCredential(
                userIdInput = "42",
                replacementApiKeyInput = "",
                configuredApiKey = null,
            ),
        )
    }

    @Test
    fun `recoverable error keeps user id but never keeps secret input`() {
        val presentation = credentialRecoveryPresentation(
            currentUserIdInput = "42",
            statusLabel = "Source credentials are temporarily unavailable — try again",
            clearUserId = false,
        )

        assertEquals("42", presentation.userIdInput)
        assertEquals("", presentation.apiKeyInput)
        assertEquals(
            "Source credentials are temporarily unavailable — try again",
            presentation.statusLabel,
        )
    }

    @Test
    fun `unrecoverable state clears user id and secret input`() {
        val presentation = credentialRecoveryPresentation(
            currentUserIdInput = "42",
            statusLabel = "Source credentials need to be reconnected",
            clearUserId = true,
        )

        assertEquals("", presentation.userIdInput)
        assertEquals("", presentation.apiKeyInput)
    }
}
