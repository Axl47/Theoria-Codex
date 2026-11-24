// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.extensionhost.failingAsIo
import okhttp3.Interceptor
import okhttp3.Response

class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = failingAsIo { chain.proceed(chain.request()) }
}

class UserAgentInterceptor(private val defaultUserAgentProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("User-Agent").isNullOrEmpty()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", defaultUserAgentProvider()).build())
    }
}
