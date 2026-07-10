package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableSourceAccountStoreTest {
    @Test
    fun `rule34 capability changes without replacing the account store`() = runTest {
        val delegate = FakeRecoverableCredentialsStore()
        val store = ObservableSourceAccountStore(delegate)
        val baseSources = store.availableSources.value

        assertFalse(SourceKey.RULE34XXX in baseSources)

        store.saveRule34XxxCredentials(Rule34XxxCredentials(userId = "user", apiKey = "key"))
        assertTrue(SourceKey.RULE34XXX in store.availableSources.value)
        assertEquals(baseSources, store.availableSources.value - SourceKey.RULE34XXX)

        store.clearRule34XxxCredentials()
        assertEquals(baseSources, store.availableSources.value)
    }

    @Test
    fun `reading persisted rule34 credentials publishes their capability`() = runTest {
        val delegate = FakeRecoverableCredentialsStore(
            rule34Credentials = Rule34XxxCredentials(userId = "persisted", apiKey = "secret"),
        )
        val store = ObservableSourceAccountStore(delegate)

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)

        assertEquals(delegate.rule34Credentials, store.getRule34XxxCredentials())
        assertTrue(SourceKey.RULE34XXX in store.availableSources.value)
    }

    @Test
    fun `confirmed recovery reset removes credential gated capabilities`() = runTest {
        val delegate = FakeRecoverableCredentialsStore(
            rule34Credentials = Rule34XxxCredentials(userId = "user", apiKey = "key"),
        )
        val store = ObservableSourceAccountStore(delegate)
        store.getRule34XxxCredentials()
        delegate.mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired

        assertTrue(store.resetAfterReconnectRequired())

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    @Test
    fun `credential recovery failure removes gated capabilities after any account operation`() = runTest {
        val delegate = FakeRecoverableCredentialsStore()
        val store = ObservableSourceAccountStore(delegate)
        store.saveRule34XxxCredentials(Rule34XxxCredentials(userId = "user", apiKey = "key"))
        delegate.mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired

        store.getPixivTokens()

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)
    }
}

private class FakeRecoverableCredentialsStore(
    var pixivTokens: PixivAuthTokens? = null,
    var gelbooruCredentials: GelbooruCredentials? = null,
    var rule34Credentials: Rule34XxxCredentials? = null,
) : RecoverableSourceCredentialsStore {
    val mutableRecoveryState = MutableStateFlow<CredentialStoreRecoveryState>(
        CredentialStoreRecoveryState.Ready,
    )
    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = mutableRecoveryState

    override suspend fun getPixivTokens(): PixivAuthTokens? = pixivTokens

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        pixivTokens = tokens
    }

    override suspend fun clearPixivTokens() {
        pixivTokens = null
    }

    override suspend fun getGelbooruCredentials(): GelbooruCredentials? = gelbooruCredentials

    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        gelbooruCredentials = credentials
    }

    override suspend fun clearGelbooruCredentials() {
        gelbooruCredentials = null
    }

    override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = rule34Credentials

    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        rule34Credentials = credentials
    }

    override suspend fun clearRule34XxxCredentials() {
        rule34Credentials = null
    }

    override suspend fun resetAfterReconnectRequired(): Boolean {
        pixivTokens = null
        gelbooruCredentials = null
        rule34Credentials = null
        mutableRecoveryState.value = CredentialStoreRecoveryState.Ready
        return true
    }
}
