package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMetadataTest {
    @Test
    fun `exposed real sources hide rule34xxx until configured`() {
        val hidden = exposedRealSources(rule34XxxConfigured = false)
        val visible = exposedRealSources(rule34XxxConfigured = true)

        assertTrue(SourceKey.IWARA in hidden)
        assertTrue(SourceKey.IWARA in visible)
        assertTrue(SourceKey.HITOMI in hidden)
        assertTrue(SourceKey.HITOMI in visible)
        assertFalse(SourceKey.RULE34XXX in hidden)
        assertTrue(SourceKey.RULE34XXX in visible)
        assertTrue(SourceKey.RULE34PAHEAL in hidden)
        assertTrue(SourceKey.RULE34VIDEO in hidden)
        assertTrue(SourceKey.RULE34GEN in hidden)
        assertFalse(SourceKey.AIBOORU in hidden)
        assertFalse(SourceKey.AIBOORU in visible)
    }

    @Test
    fun `catalog covers every source with stable ordering and explicit exposure`() {
        val presentations = SourcePresentationCatalog.orderedPresentations()

        assertEquals(SourceKey.entries.toSet(), presentations.map { it.source }.toSet())
        assertEquals(presentations.indices.toList(), presentations.map { it.order })
        assertEquals(
            listOf(
                SourceKey.GELBOORU,
                SourceKey.PIXIV,
                SourceKey.NHENTAI,
                SourceKey.HITOMI,
                SourceKey.IWARA,
                SourceKey.RULE34XXX,
                SourceKey.RULE34PAHEAL,
                SourceKey.RULE34VIDEO,
                SourceKey.RULE34GEN,
                SourceKey.AIBOORU,
            ),
            presentations.map { it.source },
        )
        assertEquals(SourceExposure.ADAPTER_ONLY, SourceKey.AIBOORU.presentation().exposure)
        assertEquals(SourceExposure.CREDENTIAL_GATED, SourceKey.RULE34XXX.presentation().exposure)
        assertEquals(SourcePresentationGroup.RULE34, SourceKey.RULE34VIDEO.presentation().group)
        assertTrue(SourceKey.RULE34VIDEO.isRule34Family())
        assertFalse(SourceKey.PIXIV.isRule34Family())
    }

    @Test
    fun `request headers use source referer`() {
        assertEquals("https://rule34video.com/", SourceKey.RULE34VIDEO.requestHeaders()["Referer"])
        assertEquals("Mozilla/5.0", SourceKey.RULE34VIDEO.requestHeaders()["User-Agent"])
        assertEquals("https://rule34.paheal.net/", SourceKey.RULE34PAHEAL.referer())
        assertEquals("https://www.iwara.tv/", SourceKey.IWARA.referer())
        assertEquals("Iwara", SourceKey.IWARA.displayName())
        assertEquals("https://hitomi.la/", SourceKey.HITOMI.requestHeaders()["Referer"])
        assertEquals("Hitomi", SourceKey.HITOMI.displayName())
    }
}
