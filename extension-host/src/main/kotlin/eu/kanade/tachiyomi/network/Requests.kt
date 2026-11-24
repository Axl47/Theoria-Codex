// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
@file:Suppress("FunctionName", "FunctionNaming")

package eu.kanade.tachiyomi.network

import kotlin.time.Duration.Companion.minutes
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10.minutes).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun GET(url: String, headers: Headers = DEFAULT_HEADERS, cache: CacheControl = DEFAULT_CACHE_CONTROL): Request =
    GET(url.toHttpUrl(), headers, cache)

fun GET(url: HttpUrl, headers: Headers = DEFAULT_HEADERS, cache: CacheControl = DEFAULT_CACHE_CONTROL): Request =
    Request.Builder().url(url).headers(headers).cacheControl(cache).build()

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder().url(url).post(body).headers(headers).cacheControl(cache).build()
