package com.theoriacodex.sources.pixiv

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.common.isSuccessful
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.parseJsonObject
import com.theoriacodex.sources.common.sourceNetworkFailure
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PixivAuthApi(
    private val httpClient: SourceHttpClient,
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val refreshMutex = Mutex()

    fun buildAuthorizationUrl(
        codeChallenge: String,
        state: String? = null,
        redirectUri: String? = null,
    ): String {
        val query = linkedMapOf(
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "client" to "pixiv-android",
        )
        if (!state.isNullOrBlank()) {
            query["state"] = state
        }
        if (!redirectUri.isNullOrBlank()) {
            query["redirect_uri"] = redirectUri
        }
        return buildUrl(AUTHORIZE_URL, query)
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): PixivAuthTokens {
        val response = try {
            httpClient.postForm(
                url = TOKEN_URL,
                form = mapOf(
                    "client_id" to PIXIV_CLIENT_ID,
                    "client_secret" to PIXIV_CLIENT_SECRET,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "code_verifier" to codeVerifier,
                    "redirect_uri" to redirectUri,
                ),
            )
        } catch (error: IOException) {
            sourceNetworkFailure("Pixiv token exchange", error)
        }
        if (!response.isSuccessful()) {
            throw SourceAdapterException(
                reason = mapAuthFailure(response.statusCode),
                message = "Pixiv token exchange failed (${response.statusCode})",
            )
        }
        return parseTokenResponse(response.body)
    }

    suspend fun refresh(refreshToken: String): PixivAuthTokens {
        return refreshMutex.withLock {
            val response = try {
                httpClient.postForm(
                    url = TOKEN_URL,
                    form = mapOf(
                        "client_id" to PIXIV_CLIENT_ID,
                        "client_secret" to PIXIV_CLIENT_SECRET,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                    ),
                )
            } catch (error: IOException) {
                sourceNetworkFailure("Pixiv token refresh", error)
            }
            if (!response.isSuccessful()) {
                throw SourceAdapterException(
                    reason = mapAuthFailure(response.statusCode),
                    message = "Pixiv token refresh failed (${response.statusCode})",
                )
            }
            parseTokenResponse(response.body)
        }
    }

    private fun parseTokenResponse(body: String): PixivAuthTokens {
        val root = parseJsonObject(body, gson, "Pixiv token response")

        val accessToken = root.stringValue("access_token")
        val refreshToken = root.stringValue("refresh_token")
        val expiresIn = root.longValue("expires_in")

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresIn == null) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Pixiv token response missing required fields",
            )
        }

        return PixivAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = clock() + (expiresIn * 1000L),
        )
    }
}

private fun mapAuthFailure(statusCode: Int): SourceFailureReason {
    return classifyHttpFailure(
        statusCode = statusCode,
        badRequestReason = SourceFailureReason.AUTH_REQUIRED,
        unauthorizedReason = SourceFailureReason.AUTH_EXPIRED,
        forbiddenReason = SourceFailureReason.AUTH_EXPIRED,
    )
}

private fun buildUrl(base: String, query: Map<String, String>): String {
    if (query.isEmpty()) return base
    val separator = if ("?" in base) "&" else "?"
    return base + separator + query.entries.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun String.urlEncode(): String {
    return java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}

const val PIXIV_CLIENT_ID: String = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
const val PIXIV_CLIENT_SECRET: String = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
const val DEFAULT_PIXIV_REDIRECT_URI: String = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"
private const val AUTHORIZE_URL: String = "https://app-api.pixiv.net/web/v1/login"
private const val TOKEN_URL: String = "https://oauth.secure.pixiv.net/auth/token"
