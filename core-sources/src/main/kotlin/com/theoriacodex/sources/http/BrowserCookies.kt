package com.theoriacodex.sources.http

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

interface BrowserCookieStore {
    fun cookieHeader(url: String): String?

    fun setCookie(url: String, setCookie: String)

    fun flush()

    fun expire(url: String, name: String? = null)
}

const val CLEARANCE_COOKIE = "cf_clearance"

fun BrowserCookieStore.cookie(url: String, name: String): String? = cookieValue(runCatching { cookieHeader(url) }.getOrNull(), name)

fun cookieValue(header: String?, name: String): String? =
    header?.split(';')?.firstNotNullOfOrNull { pair -> pair.trim().takeIf { it.substringBefore('=') == name }?.substringAfter('=') }

fun BrowserCookieStore.hasNewClearance(url: String, old: String?): Boolean =
    cookie(url, CLEARANCE_COOKIE).let { it != null && it != old }

class BrowserCookieJar(private val store: BrowserCookieStore, private val allows: (host: String) -> Boolean) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!allows(url.host)) return emptyList()
        val header = runCatching { store.cookieHeader(url.toString()) }.getOrNull() ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!allows(url.host)) return
        cookies.forEach { cookie -> runCatching { store.setCookie(url.toString(), cookie.toString()) } }
    }
}
