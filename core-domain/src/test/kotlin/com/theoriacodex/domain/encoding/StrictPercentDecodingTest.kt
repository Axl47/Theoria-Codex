package com.theoriacodex.domain.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrictPercentDecodingTest {
    @Test
    fun `decodes escaped utf8 and preserves literal plus`() {
        assertEquals("Miku Nakano+猫", decodePercentEncodedUtf8Strict("Miku%20Nakano+%E7%8C%AB"))
    }

    @Test
    fun `accepts unescaped unicode scalar values`() {
        assertEquals("猫 🌄", decodePercentEncodedUtf8Strict("猫%20🌄"))
    }

    @Test
    fun `rejects malformed escapes and malformed utf8`() {
        assertNull(decodePercentEncodedUtf8Strict("artist%2"))
        assertNull(decodePercentEncodedUtf8Strict("artist%GG"))
        assertNull(decodePercentEncodedUtf8Strict("%C3%28"))
    }

    @Test
    fun `rejects unpaired utf16 surrogates`() {
        assertNull(decodePercentEncodedUtf8Strict("artist\uD800"))
    }
}
