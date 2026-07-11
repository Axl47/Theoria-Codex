package com.theoriacodex.app.sourceauth

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.pixiv.PixivAuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixivPkceControllerDeviceTest {
    @Test
    fun controllerRecreationCompletesAndThenRejectsAReplay() = runBlocking {
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient()
        val credentials = RecordingCredentialsProvider()
        val firstController = controller(store, http, credentials)
        val state = requireNotNull(firstController.startAuthorizationUri().getQueryParameter("state"))
        val storedSession = requireNotNull(store.read())
        val callback = callback(state)

        val recreatedController = controller(store, http, credentials)
        val completed = recreatedController.handleAuthorizationCallback(callback)
        val replay = recreatedController.handleAuthorizationCallback(callback)

        assertTrue(completed.isSuccess)
        assertEquals(storedSession.codeVerifier, http.forms.single()["code_verifier"])
        assertEquals("access", credentials.savedPixivTokens?.accessToken)
        assertNull(store.read())
        assertTrue(replay.isFailure)
        assertEquals("Pixiv authorization session not started", replay.exceptionOrNull()?.message)
        assertEquals(1, http.postCalls)
    }

    @Test
    fun missingMismatchAndExpiredStateNeverReachTheTokenExchange() = runBlocking {
        var now = BASE_TIME_MS
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient()
        val controller = controller(store, http, RecordingCredentialsProvider()) { now }
        val state = requireNotNull(controller.startAuthorizationUri().getQueryParameter("state"))

        val missing = controller.handleAuthorizationCallback(callback(state = null))
        val mismatch = controller.handleAuthorizationCallback(callback(state = "wrong-state"))
        now += DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS
        val expired = controller.handleAuthorizationCallback(callback(state = state))

        assertEquals("Pixiv authorization state mismatch", missing.exceptionOrNull()?.message)
        assertEquals("Pixiv authorization state mismatch", mismatch.exceptionOrNull()?.message)
        assertEquals("Pixiv authorization session expired", expired.exceptionOrNull()?.message)
        assertEquals(0, http.postCalls)
        assertNotNull("validation failures preserve the session", store.read())
    }

    @Test
    fun transientTokenExchangeFailurePreservesTheSessionForRetry() = runBlocking {
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient(statusCodes = ArrayDeque(listOf(503, 200)))
        val credentials = RecordingCredentialsProvider()
        val controller = controller(store, http, credentials)
        val state = requireNotNull(controller.startAuthorizationUri().getQueryParameter("state"))
        val callback = callback(state)

        val first = controller.handleAuthorizationCallback(callback)
        assertTrue(first.isFailure)
        assertNotNull(store.read())

        val retry = controller.handleAuthorizationCallback(callback)
        assertTrue(retry.isSuccess)
        assertEquals(2, http.postCalls)
        assertNull(store.read())
    }

    @Test
    fun transientCredentialSaveFailurePreservesTheSessionForRetry() = runBlocking {
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient()
        val credentials = RecordingCredentialsProvider(remainingSaveFailures = 1)
        val controller = controller(store, http, credentials)
        val state = requireNotNull(controller.startAuthorizationUri().getQueryParameter("state"))
        val callback = callback(state)

        val first = controller.handleAuthorizationCallback(callback)
        assertTrue(first.isFailure)
        assertNotNull(store.read())

        val retry = controller.handleAuthorizationCallback(callback)
        assertTrue(retry.isSuccess)
        assertEquals(2, http.postCalls)
        assertNull(store.read())
    }

    @Test
    fun callbackPropagatesCredentialPersistenceCancellationUnchanged() = runBlocking {
        val expected = CancellationException("activity left during callback")
        val store = InMemoryPixivPkceSessionStore()
        val controller = controller(
            store = store,
            http = RecordingTokenHttpClient(),
            credentials = CancellingCredentialsProvider(expected),
        )
        val state = requireNotNull(controller.startAuthorizationUri().getQueryParameter("state"))

        var thrown: CancellationException? = null
        try {
            controller.handleAuthorizationCallback(callback(state))
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(expected, thrown)
        assertNotNull("cancellation before terminal save must not consume the session", store.read())
    }

    @Test
    fun cancellationAfterCredentialSaveReturnsTerminalSuccessAndConsumesSession() = runBlocking {
        val store = InMemoryPixivPkceSessionStore()
        lateinit var callbackJob: Job
        val credentials = RecordingCredentialsProvider(
            afterSave = {
                callbackJob.cancel(CancellationException("activity left after credentials saved"))
            },
        )
        val controller = controller(store, RecordingTokenHttpClient(), credentials)
        val state = requireNotNull(controller.startAuthorizationUri().getQueryParameter("state"))
        var observedResult: Result<Unit>? = null

        callbackJob = launch(start = CoroutineStart.LAZY) {
            observedResult = controller.handleAuthorizationCallback(callback(state))
        }
        callbackJob.start()
        callbackJob.join()

        assertTrue("the shell must observe terminal success", requireNotNull(observedResult).isSuccess)
        assertEquals("access", credentials.savedPixivTokens?.accessToken)
        assertNull("terminal success consumes the one-shot session", store.read())
        assertTrue("the surrounding callback collector was cancelled", callbackJob.isCancelled)
    }

    private fun controller(
        store: PixivPkceSessionStore,
        http: RecordingTokenHttpClient,
        credentials: SourceCredentialsProvider,
        clock: () -> Long = { BASE_TIME_MS },
    ): PixivPkceController {
        return PixivPkceController(
            authApi = PixivAuthApi(httpClient = http, clock = clock),
            credentialsProvider = credentials,
            sessionStore = store,
            clock = clock,
        )
    }

    private fun callback(state: String?): android.net.Uri {
        val stateParameter = state?.let { value -> "&state=$value" }.orEmpty()
        return "theoriacodex://pixiv-auth/callback?code=authorization-code$stateParameter".toUri()
    }

    private class RecordingTokenHttpClient(
        private val statusCodes: ArrayDeque<Int> = ArrayDeque(listOf(200)),
    ) : SourceHttpClient {
        var postCalls: Int = 0
            private set
        val forms = mutableListOf<Map<String, String>>()

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("GET is not used for an authorization-code exchange")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            postCalls += 1
            forms += form
            val statusCode = if (statusCodes.size > 1) statusCodes.removeFirst() else statusCodes.first()
            return SourceHttpResponse(
                statusCode = statusCode,
                body = if (statusCode in 200..299) {
                    """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""
                } else {
                    "{}"
                },
            )
        }
    }

    private class RecordingCredentialsProvider(
        private var remainingSaveFailures: Int = 0,
        private val afterSave: () -> Unit = {},
    ) : SourceCredentialsProvider {
        var savedPixivTokens: PixivAuthTokens? = null
            private set

        override suspend fun getPixivTokens(): PixivAuthTokens? = savedPixivTokens
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
            if (remainingSaveFailures > 0) {
                remainingSaveFailures -= 1
                throw IllegalStateException("temporary credential storage failure")
            }
            savedPixivTokens = tokens
            afterSave()
        }
        override suspend fun clearPixivTokens() {
            savedPixivTokens = null
        }
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private class CancellingCredentialsProvider(
        private val cancellation: CancellationException,
    ) : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? = null
        override suspend fun savePixivTokens(tokens: PixivAuthTokens): Unit = throw cancellation
        override suspend fun clearPixivTokens() = Unit
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private companion object {
        const val BASE_TIME_MS = 1_000_000L
    }
}
