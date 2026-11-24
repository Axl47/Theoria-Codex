package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.RoutedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `only getCheckedOrNull reads a 404 as no body`() = runTest {
        assertEquals("body", client.getChecked("Site", "https://site/ok"))
        assertEquals("body", client.getCheckedOrNull("Site", "https://site/ok", emptyMap()))
        assertNull(client.getCheckedOrNull("Site", "https://site/missing", emptyMap()))

        val missing = runCatching { client.getChecked("Site", "https://site/missing") }.exceptionOrNull()
        val broken = runCatching { client.getCheckedOrNull("Site", "https://site/broken", emptyMap()) }.exceptionOrNull()
        assertEquals("Site request failed (404)", (missing as SourceAdapterException).message)
        assertEquals("Site request failed (500)", (broken as SourceAdapterException).message)
    }
}
