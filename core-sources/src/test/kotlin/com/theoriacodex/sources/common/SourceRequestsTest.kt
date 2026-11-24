package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.RoutedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRequestsTest {
    private val client = RoutedHttpClient(
        mapOf(
            "https://site/ok" to SourceHttpResponse(200, "body"),
            "https://site/missing" to SourceHttpResponse(404, "gone"),
            "https://site/broken" to SourceHttpResponse(500, "oops"),
        ),
    )

    @Test
    fun `getChecked answers notFound for a 404 and fails it otherwise`() = runTest {
        assertEquals("body", client.getChecked("Site", "https://site/ok", notFound = { "unused" }))
        assertEquals("", client.getChecked("Site", "https://site/missing", notFound = { "" }))

        val missing = runCatching { client.getChecked("Site", "https://site/missing") }.exceptionOrNull()
        val broken = runCatching { client.getChecked("Site", "https://site/broken", notFound = { "" }) }.exceptionOrNull()
        assertEquals("Site request failed (404)", (missing as SourceAdapterException).message)
        assertEquals("Site request failed (500)", (broken as SourceAdapterException).message)
    }
}
