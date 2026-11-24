package com.theoriacodex.app.sourceauth

import android.net.Uri
import androidx.core.net.toUri
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.pixiv.DEFAULT_PIXIV_REDIRECT_URI
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PixivPkceController constructor(
    private val authApi: PixivAuthApi,
    private val credentialsProvider: SourceCredentialsProvider,
    private val sessionStore: PixivPkceSessionStore,
    private val redirectUri: String = DEFAULT_PIXIV_REDIRECT_URI,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionLifetimeMs: Long = DEFAULT_PIXIV_PKCE_SESSION_LIFETIME_MS,
) {
    suspend fun startAuthorizationUri(): Uri = pixivPkceControllerMutex.withLock {
        val session = PixivPkceSession(
            state = randomToken(length = 24),
            codeVerifier = randomToken(length = 64),
            createdAtEpochMs = clock(),
        )
        val authorizationUri = authApi.buildAuthorizationUrl(
            codeChallenge = session.codeVerifier.sha256Base64Url(),
            state = session.state,
        ).toUri()
        sessionStore.writeVerified(session)
        authorizationUri
    }

    fun isAuthorizationCallback(uri: Uri): Boolean = uri.callbackKind() != null

    suspend fun handleAuthorizationCallback(uri: Uri): Result<Unit> {
        return runCatchingPreservingCancellation {
            pixivPkceControllerMutex.withLock {
                if (!isAuthorizationCallback(uri)) {
                    throw IllegalStateException("Unsupported Pixiv callback URI")
                }
                val session = sessionStore.read()
                    ?: throw IllegalStateException("Pixiv authorization session not started")
                val code = PixivPkceSessionPolicy.requireAuthorizationCode(
                    session = session,
                    callbackState = uri.getQueryParameter("state"),
                    authorizationCode = uri.getQueryParameter("code"),
                    authorizationError = uri.getQueryParameter("error"),
                    nowEpochMs = clock(),
                    sessionLifetimeMs = sessionLifetimeMs,
                    allowMissingCallbackState = uri.callbackKind() == PixivCallbackKind.PROVIDER_NATIVE,
                )

                val tokens = authApi.exchangeAuthorizationCode(
                    code = code,
                    codeVerifier = session.codeVerifier,
                    redirectUri = redirectUri,
                )
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    credentialsProvider.savePixivTokens(tokens)
                    val consumed = sessionStore.consume(session)
                    check(consumed) {
                        "Pixiv authorization session could not be finalized"
                    }
                }
            }
        }
    }

    private fun randomToken(length: Int): String {
        return buildString(length) {
            repeat(length) {
                append(tokenAlphabet[random.nextInt(tokenAlphabet.size)])
            }
        }
    }
}

private val tokenAlphabet: List<Char> = PIXIV_PKCE_TOKEN_ALPHABET.toList()
private val pixivPkceControllerMutex = Mutex()

private fun Uri.callbackKind(): PixivCallbackKind? = pixivCallbackKind(scheme, host, path)

private fun String.sha256Base64Url(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
