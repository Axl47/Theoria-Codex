package com.theoriacodex.app.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewChallengeBrowserTest {
    @Test
    fun `the hidden browser gets only the headers a browser would send itself`() {
        val request = Request.Builder().url("https://site.test/")
            .header("Authorization", "Bearer secret")
            .header("Cookie", "session=1")
            .header("X-Api-Key", "secret")
            .header("User-Agent", "agent/1")
            .header("Accept-Language", "en")
            .header("Referer", "https://site.test/home")
            .build()

        assertEquals(mapOf("Accept-Language" to "en", "Referer" to "https://site.test/home"), browserHeaders(request))
    }
}
