package com.theoriacodex.app.viewer

import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PixivUgoiraClientTest {
    @Test
    fun `load propagates credential cancellation without caching a partial playback`() = runTest {
        val expected = CancellationException("viewer left")
        val client = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(expected),
            httpClient = UnusedHttpClient,
        )

        var thrown: CancellationException? = null
        try {
            client.load("42")
        } catch (error: CancellationException) {
            thrown = error
        }

        assertNotNull(thrown)
        assertEquals(expected.message, thrown?.message)
        assertNull(client.cached("42"))
    }

    private class CancellingCredentialsProvider(
        private val cancellation: CancellationException,
    ) : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? = throw cancellation
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private object UnusedHttpClient : SourceHttpClient {
        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")
    }
}
