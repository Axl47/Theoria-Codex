// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * The HTTP client every extension starts from; an extension adds its own interceptors with `client.newBuilder()`.
 * Unlike Mihon's, [client] does not solve Cloudflare challenges.
 */
class NetworkHelper(val client: OkHttpClient, private val userAgent: () -> String) {
    @Deprecated("The regular client is the only client")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent()
}
