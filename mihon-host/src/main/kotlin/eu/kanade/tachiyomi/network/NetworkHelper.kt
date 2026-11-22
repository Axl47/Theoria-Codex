// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

/**
 * The HTTP client every extension starts from; an extension adds its own interceptors with `client.newBuilder()`.
 * As in Mihon, [client] shares the system browser's cookies and tries to pass a Cloudflare check out of sight; a check
 * that needs a tap fails with `BrowserCheckException`, which the Series screens offer to open.
 */
class NetworkHelper(val client: OkHttpClient, private val userAgent: () -> String) {
    @Deprecated("The regular client is the only client")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent()
}
