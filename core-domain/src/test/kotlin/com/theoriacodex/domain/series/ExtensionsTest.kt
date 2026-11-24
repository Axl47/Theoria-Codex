package com.theoriacodex.domain.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionsTest {
    @Test
    fun `the device's languages are on by default`() {
        assertEquals(setOf("es", "pt-BR"), defaultLanguages(listOf("en", "es", "pt-BR", "ko"), deviceLanguages = listOf("pt-PT", "es-PR")))
        assertEquals(setOf("zh-Hans", "zh-Hant"), defaultLanguages(listOf("en", "zh-Hans", "zh-Hant"), deviceLanguages = listOf("zh-TW")))
    }

    @Test
    fun `English is on when no source is in a device language`() {
        assertEquals(setOf("en"), defaultLanguages(listOf("all", "ja", "en"), deviceLanguages = listOf("de-DE")))
    }

    @Test
    fun `the first source is on when there is no English one either`() {
        assertEquals(setOf("ja"), defaultLanguages(listOf("ja", "ko"), deviceLanguages = listOf("de-DE")))
        assertEquals(emptySet<String>(), defaultLanguages(emptyList(), deviceLanguages = listOf("de-DE")))
    }

    @Test
    fun `only a certificate the user turned on is trusted`() {
        val choices = ExtensionChoices(trusted = mapOf("a" to "key-1", "unsigned" to ""))

        assertTrue(choices.trusts("a", "key-1"))
        assertFalse(choices.trusts("a", "key-2"))
        assertFalse(choices.trusts("b", "key-1"))
        assertFalse(choices.trusts("a", null))
        assertFalse(choices.trusts("unsigned", ""))
    }
}
