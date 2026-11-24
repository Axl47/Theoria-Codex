// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

class NetworkHelper(val client: OkHttpClient, private val userAgent: () -> String) {
    @Deprecated("The regular client is the only client")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent()
}
