package com.theoriacodex.domain.encoding

import org.junit.Assert.assertEquals
import org.junit.Test

class PercentEncodingTest {
    @Test
    fun `encodes one path segment or query value with spaces as percent twenty`() {
        val value = "a b+c/d~e*f-g_h.i&j=k猫"

        assertEquals("a%20b%2Bc%2Fd%7Ee*f-g_h.i%26j%3Dk%E7%8C%AB", value.percentEncode())
        assertEquals(value, decodePercentEncodedUtf8Strict(value.percentEncode()))
    }
}
