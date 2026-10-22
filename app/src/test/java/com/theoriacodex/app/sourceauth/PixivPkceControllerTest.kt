package com.theoriacodex.app.sourceauth

import androidx.core.net.toUri
import android.app.Application
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.pixiv.PixivAuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** URI/controller orchestration belongs on the host; encrypted Android stores have device tests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PixivPkceControllerTest {
    @Test
    fun controllerRecreationCompletesAndThenRejectsAReplay() = runTest {
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
    fun missingMismatchAndExpiredStateNeverReachTheTokenExchange() = runTest {
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
    fun pixivNativeCallbackCompletesWithoutForwardingBrowserState() = runTest {
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient()
        val credentials = RecordingCredentialsProvider()
        val controller = controller(store, http, credentials)
        controller.startAuthorizationUri()

        val completed = controller.handleAuthorizationCallback(
            "pixiv://account/login?code=authorization-code".toUri()
        )

        assertTrue(completed.isSuccess)
        assertEquals(1, http.postCalls)
        assertEquals("access", credentials.savedPixivTokens?.accessToken)
        assertNull(store.read())
    }

    @Test
    fun pixivNativeCallbackStillRejectsAConflictingState() = runTest {
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient()
        val controller = controller(store, http, RecordingCredentialsProvider())
        controller.startAuthorizationUri()

        val result = controller.handleAuthorizationCallback(
            "pixiv://account/login?code=authorization-code&state=wrong-state".toUri()
        )

        assertEquals("Pixiv authorization state mismatch", result.exceptionOrNull()?.message)
        assertEquals(0, http.postCalls)
        assertNotNull(store.read())
    }

    @Test
    fun transientTokenExchangeFailurePreservesTheSessionForRetry() = runTest {
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
    fun transientCredentialSaveFailurePreservesTheSessionForRetry() = runTest {
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
    fun callbackPropagatesCredentialPersistenceCancellationUnchanged() = runTest {
        val expected = CallbackCancellation("activity left during callback")
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
    fun cancellationAfterCredentialSaveReturnsTerminalSuccessAndConsumesSession() = runTest {
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

    @Test
    fun competingControllersExchangeAndSaveTheOneShotSessionOnlyOnce() = runTest {
        val exchangeStarted = CompletableDeferred<Unit>()
        val releaseExchange = CompletableDeferred<Unit>()
        val store = InMemoryPixivPkceSessionStore()
        val http = RecordingTokenHttpClient(beforeResponse = {
            exchangeStarted.complete(Unit)
            releaseExchange.await()
        })
        val credentials = RecordingCredentialsProvider()
        val firstController = controller(store, http, credentials)
        val state = requireNotNull(firstController.startAuthorizationUri().getQueryParameter("state"))
        val secondController = controller(store, http, credentials)

        val first = async { firstController.handleAuthorizationCallback(callback(state)) }
        exchangeStarted.await()
        // Start the competing callback while the original exchange is still suspended.
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            secondController.handleAuthorizationCallback(callback(state))
        }
        assertEquals(1, http.postCalls)
        assertNull(credentials.savedPixivTokens)
        releaseExchange.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertEquals("Pixiv authorization session not started", second.await().exceptionOrNull()?.message)
        assertEquals(1, http.postCalls)
        assertEquals(1, credentials.saveCalls)
        assertEquals("access", credentials.savedPixivTokens?.accessToken)
        assertNull(store.read())
    }

    @Test
    fun cancellationDuringTokenExchangePreservesTheExactSessionForAnotherController() = runTest {
        val exchangeStarted = CompletableDeferred<Unit>()
        val releaseExchange = CompletableDeferred<Unit>()
        val store = InMemoryPixivPkceSessionStore()
        val credentials = RecordingCredentialsProvider()
        val http = RecordingTokenHttpClient(beforeResponse = {
            exchangeStarted.complete(Unit)
            releaseExchange.await()
        })
        val firstController = controller(store, http, credentials)
        val state = requireNotNull(firstController.startAuthorizationUri().getQueryParameter("state"))
        val originalSession = requireNotNull(store.read())

        val pending = launch { firstController.handleAuthorizationCallback(callback(state)) }
        exchangeStarted.await()
        pending.cancelAndJoin()
        assertEquals(originalSession, store.read())
        assertNull(credentials.savedPixivTokens)
        assertEquals(0, credentials.saveCalls)

        releaseExchange.complete(Unit)
        val retry = controller(store, http, credentials).handleAuthorizationCallback(callback(state))
        assertTrue(retry.isSuccess)
        assertEquals(2, http.postCalls)
        assertTrue(http.forms.all { it["code_verifier"] == originalSession.codeVerifier })
        assertEquals(1, credentials.saveCalls)
        assertNull(store.read())
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
        private val beforeResponse: suspend () -> Unit = {},
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
            beforeResponse()
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
        var saveCalls: Int = 0
            private set

        override suspend fun getPixivTokens(): PixivAuthTokens? = savedPixivTokens
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
            saveCalls += 1
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

    // Host debug stack recovery may clone ordinary exceptions; this sentinel tests exact propagation.
    @OptIn(ExperimentalCoroutinesApi::class)
    private class CallbackCancellation(message: String) :
        CancellationException(message), CopyableThrowable<CallbackCancellation> {
        override fun createCopy(): CallbackCancellation? = null
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
