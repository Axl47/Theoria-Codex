package com.theoriacodex.domain.series

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesKindTest {
    @Test
    fun `an extension source's kind is read from its id, which parses as a custom-site id (ADR 0007)`() {
        val anime = SourceKey.parseOrThrow("${ANIYOMI_SOURCE_ID_PREFIX}2499283573021220255")
        val manga = SourceKey.parseOrThrow("${MIHON_SOURCE_ID_PREFIX}2499283573021220255")

        assertEquals(SeriesKind.ANIME, anime.seriesKind)
        assertEquals(SeriesKind.MANGA, manga.seriesKind)
        assertTrue(anime.isExtensionSource() && manga.isExtensionSource())
        assertTrue("The same number on each kind stays two sources", anime != manga)
    }

    @Test
    fun `sources that run no extension are not extension sources (ADR 0007)`() {
        val sites = listOf("custom_safebooru_org", "custom_aniyomi-fan_org", "custom_aniyomi_example_org", "custom_mihon_example_org")
        (sites.map(SourceKey::parseOrThrow) + SourceKey.PIXIV).forEach {
            assertFalse(it.id, it.isExtensionSource())
            assertEquals(SeriesKind.MANGA, it.seriesKind)
        }
    }
}
