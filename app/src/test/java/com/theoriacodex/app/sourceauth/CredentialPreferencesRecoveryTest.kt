package com.theoriacodex.app.sourceauth

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPreferencesRecoveryTest {
    @Test
    fun `unreadable restored preferences require reconnect without deleting storage`() {
        var resetCount = 0
        val preferences = RecoverableCredentialPreferences(
            openPreferences = { throw AEADBadTagException("restored without its device key") },
            resetStorage = { resetCount += 1 },
        )

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, preferences.recoveryState.value)
        assertEquals(0, resetCount)
        assertNull(preferences.read { getString("secret") })
        assertFalse(preferences.update { putString("secret", "replacement") })
        assertEquals(0, resetCount)
    }

    @Test
    fun `explicit reconnect reset clears unreadable storage and reopens preferences`() {
        var resetCount = 0
        var openCount = 0
        val replacement = FakeCredentialPreferences()
        val preferences = RecoverableCredentialPreferences(
            openPreferences = {
                openCount += 1
                if (openCount == 1) {
                    throw AEADBadTagException("restored without its device key")
                }
                replacement
            },
            resetStorage = { resetCount += 1 },
        )

        assertTrue(preferences.resetAfterReconnectRequired())

        assertEquals(1, resetCount)
        assertEquals(CredentialStoreRecoveryState.Ready, preferences.recoveryState.value)
        assertTrue(preferences.update { putString("secret", "replacement") })
        assertEquals("replacement", preferences.read { getString("secret") })
    }

    @Test
    fun `runtime decrypt failure changes state without deleting storage`() {
        var resetCount = 0
        val backing = FakeCredentialPreferences().apply {
            readFailure = AEADBadTagException("ciphertext cannot be authenticated")
        }
        val preferences = RecoverableCredentialPreferences(
            openPreferences = { backing },
            resetStorage = { resetCount += 1 },
        )

        assertNull(preferences.read { getString("secret") })

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, preferences.recoveryState.value)
        assertEquals(0, resetCount)
    }

    @Test
    fun `runtime encrypt failure requires reconnect without retrying or deleting`() {
        var resetCount = 0
        val backing = FakeCredentialPreferences().apply {
            updateFailure = AEADBadTagException("key cannot encrypt replacement")
        }
        val preferences = RecoverableCredentialPreferences(
            openPreferences = { backing },
            resetStorage = { resetCount += 1 },
        )

        assertFalse(preferences.update { putString("secret", "replacement") })

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, preferences.recoveryState.value)
        assertEquals(0, resetCount)
        assertEquals(1, backing.updateAttempts)
    }

    @Test(expected = IllegalStateException::class)
    fun `unexpected preference failure is not reclassified as reconnect required`() {
        RecoverableCredentialPreferences(
            openPreferences = { throw IllegalStateException("programming failure") },
            resetStorage = {},
        )
    }

    private class FakeCredentialPreferences : CredentialPreferences {
        private val values = mutableMapOf<String, Any>()
        var readFailure: Exception? = null
        var updateFailure: Exception? = null
        var updateAttempts: Int = 0

        override fun getString(key: String): String? {
            readFailure?.let { throw it }
            return values[key] as? String
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            readFailure?.let { throw it }
            return values[key] as? Long ?: defaultValue
        }

        override fun update(block: CredentialPreferencesEditor.() -> Unit) {
            updateAttempts += 1
            updateFailure?.let { throw it }
            object : CredentialPreferencesEditor {
                override fun putString(key: String, value: String) {
                    values[key] = value
                }

                override fun putLong(key: String, value: Long) {
                    values[key] = value
                }

                override fun remove(key: String) {
                    values.remove(key)
                }
            }.block()
        }
    }
}
