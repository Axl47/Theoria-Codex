package com.theoriacodex.app.network

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

internal class RefusedMediaKeyRetry(private val authorizationFor: (url: String) -> String?) : Interceptor {
    private val hostsNeedingKey = ConcurrentHashMap.newKeySet<String>()

    val redirectGuard = Interceptor { chain ->
        val request = chain.request()
        val stale = request.tag(SiteKeyTag::class.java) != null && authorizationFor(request.url.toString()) != request.header(AUTHORIZATION)
        chain.proceed(if (stale) request.newBuilder().removeHeader(AUTHORIZATION).build() else request)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authorization = authorizationFor(request.url.toString()) ?: return chain.proceed(request)
        val keyed = request.newBuilder().header(AUTHORIZATION, authorization).tag(SiteKeyTag::class.java, SiteKeyTag).build()
        if (request.url.host in hostsNeedingKey) return chain.proceed(keyed)
        val response = chain.proceed(request)
        if (!response.isRefusal()) return response
        response.close()
        return chain.proceed(keyed).also { retried ->
            if (!retried.isRefusal()) hostsNeedingKey += request.url.host
        }
    }

    private fun Response.isRefusal(): Boolean =
        code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN || (isSuccessful && header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true))

    private object SiteKeyTag

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
