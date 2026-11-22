// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.mihonhost.failingAsIo
import okhttp3.Interceptor
import okhttp3.Response

/*
 * The shared library compiled into every extension rebuilds the host's HTTP client, and refuses to run unless it
 * finds interceptors with these three simple class names in it (the third, `CloudflareInterceptor`, has a file of its
 * own). Renaming or removing one breaks every extension.
 */

/** Turns any failure later in the chain into an `IOException`, the only kind OkHttp hands back to a caller. */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = failingAsIo { chain.proceed(chain.request()) }
}

/** Sends the host's browser agent string on requests that name none. */
class UserAgentInterceptor(private val defaultUserAgentProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("User-Agent").isNullOrEmpty()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", defaultUserAgentProvider()).build())
    }
}
