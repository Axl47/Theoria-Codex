// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.network

/** A response whose status OkHttp does not count as successful. */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
