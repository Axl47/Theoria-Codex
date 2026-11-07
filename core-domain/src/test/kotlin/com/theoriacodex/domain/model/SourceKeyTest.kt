package com.theoriacodex.domain.model

import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKeyTest {
    @Test
    fun `built-in ids keep their historical names and round trip`() {
        val historicalNames = listOf(
            "PIXIV", "GELBOORU", "AIBOORU", "DANBOORU", "NHENTAI", "HITOMI", "IWARA",
            "RULE34XXX", "RULE34PAHEAL", "RULE34VIDEO", "RULE34GEN",
        )

        assertEquals(historicalNames, SourceKey.builtIns.map(SourceKey::id))
        SourceKey.builtIns.forEach { source ->
            assertEquals(source, SourceKey.parse(source.id))
            assertEquals(source.id, source.toString())
            assertFalse(source.isCustom)
        }
    }

    @Test
    fun `built-ins match exactly while custom ids normalize`() {
        assertNull(SourceKey.parse("pixiv"))
        assertEquals(SourceKey.PIXIV, SourceKey.parse(" PIXIV "))

        val custom = SourceKey.parse(" CUSTOM_SAFEBOORU_ORG ")
        assertEquals("custom_safebooru_org", custom?.id)
        assertTrue(custom?.isCustom == true)
    }

    @Test
    fun `malformed ids are rejected`() {
        listOf("", "UNKNOWN", "custom:safebooru.org", "custom_safebooru.org", "custom_").forEach { raw ->
            assertNull(raw, SourceKey.parse(raw))
            assertTrue(raw, runCatching { SourceKey.parseOrThrow(raw) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `natural order is the old enum order followed by custom ids`() {
        val customB = requireNotNull(SourceKey.parse("custom_b_example"))
        val customA = requireNotNull(SourceKey.parse("custom_a_example"))

        val sorted = (listOf(customB, customA) + SourceKey.builtIns.reversed()).sorted()

        assertEquals(SourceKey.builtIns + listOf(customA, customB), sorted)
    }

    @Test
    fun `a stored profile's id is its source identity`() {
        val address = (SiteAddress.parse("safebooru.org") as SiteAddressParse.Valid).address
        val profile = SiteProfile("custom_safebooru_org", address, "Safebooru", "GELBOORU_02", addedAtEpochMs = 1L)

        assertEquals(SourceKey.parse("custom_safebooru_org"), SourceKey.of(profile))
    }
}
