package com.theoriacodex.app.network

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Gives a media request an added site refused one more try with the person's API key. A site can treat files
 * like its API and turn away visitors without an account, but most never do, so a site that answers normally
 * sees exactly the request it always did. A host the key helped gets the key straight away from then on, instead
 * of spending an anonymous request each time. [authorizationFor] decides which URLs may carry a key at all.
 */
internal class RefusedMediaKeyRetry(private val authorizationFor: (url: String) -> String?) : Interceptor {
    private val hostsNeedingKey = ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authorization = authorizationFor(request.url.toString()) ?: return chain.proceed(request)
        val keyed = request.newBuilder().header("Authorization", authorization).build()
        if (request.url.host in hostsNeedingKey) return chain.proceed(keyed)
        val response = chain.proceed(request)
        if (!response.isRefusal()) return response
        response.close()
        return chain.proceed(keyed).also { retried ->
            if (!retried.isRefusal()) hostsNeedingKey += request.url.host
        }
    }

    /** A challenge page can arrive with a success status in place of the file. */
    private fun Response.isRefusal(): Boolean =
        code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN || (isSuccessful && header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true))

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
