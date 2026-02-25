package com.theoriacodex.app.sourceauth

import android.net.Uri
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.pixiv.DEFAULT_PIXIV_REDIRECT_URI
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class PixivPkceController(
    private val authApi: PixivAuthApi,
    private val credentialsProvider: SourceCredentialsProvider,
    private val redirectUri: String = DEFAULT_PIXIV_REDIRECT_URI,
    private val random: SecureRandom = SecureRandom(),
) {
    private var pendingState: String? = null
    private var pendingCodeVerifier: String? = null

    fun startAuthorizationUri(): Uri {
        val codeVerifier = randomToken(length = 64)
        val codeChallenge = codeVerifier.sha256Base64Url()
        val state = randomToken(length = 24)
        pendingState = state
        pendingCodeVerifier = codeVerifier
        return Uri.parse(
            authApi.buildAuthorizationUrl(
                codeChallenge = codeChallenge,
                state = state,
            )
        )
    }

    fun isAuthorizationCallback(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: ""
        return (scheme == "theoriacodex" && host == "pixiv-auth" && path.startsWith("/callback")) ||
            (scheme == "pixiv" && host == "account" && path.startsWith("/login"))
    }

    suspend fun handleAuthorizationCallback(uri: Uri): Result<Unit> {
        return runCatching {
            if (!isAuthorizationCallback(uri)) {
                throw IllegalStateException("Unsupported Pixiv callback URI")
            }
            val expectedState = requireNotNull(pendingState) {
                "Pixiv auth session not started"
            }
            val expectedVerifier = requireNotNull(pendingCodeVerifier) {
                "Pixiv code verifier missing"
            }
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")

            if (!error.isNullOrBlank()) {
                throw IllegalStateException("Pixiv authorization failed: $error")
            }
            if (!state.isNullOrBlank() && state != expectedState) {
                throw IllegalStateException("Pixiv authorization state mismatch")
            }
            if (code.isNullOrBlank()) {
                throw IllegalStateException("Pixiv authorization code missing")
            }

            val tokens = authApi.exchangeAuthorizationCode(
                code = code,
                codeVerifier = expectedVerifier,
                redirectUri = redirectUri,
            )
            credentialsProvider.savePixivTokens(tokens)
            pendingState = null
            pendingCodeVerifier = null
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

private val tokenAlphabet: List<Char> = (
    ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')
).toList()

private fun String.sha256Base64Url(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
