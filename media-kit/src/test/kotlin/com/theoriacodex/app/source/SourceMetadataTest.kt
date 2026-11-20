package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMetadataTest {
    private val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
    private val other = requireNotNull(SourceKey.parse("custom_booru_example__p8443"))

    @After
    fun resetCustomSources() = SourcePresentationCatalog.installCustom(emptyList())

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
        assertTrue(SourceKey.DANBOORU in hidden)
        assertTrue(SourceKey.AIBOORU in hidden)
    }

    @Test
    fun `catalog covers every source with stable ordering and explicit exposure`() {
        val presentations = SourcePresentationCatalog.orderedPresentations()

        assertEquals(SourceKey.builtIns.toSet(), presentations.map { it.source }.toSet())
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
                SourceKey.DANBOORU,
                SourceKey.AIBOORU,
            ),
            presentations.map { it.source },
        )
        assertEquals(SourcePresentationGroup.DANBOORU, SourceKey.AIBOORU.presentation().group)
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
        // Danbooru's Cloudflare challenges the generic browser agent on its media CDN.
        assertEquals(THEORIA_SOURCE_USER_AGENT, SourceKey.DANBOORU.requestHeaders()["User-Agent"])
    }

    @Test
    fun `a custom source without a profile falls back to a readable host label`() {
        val fallback = custom.presentation()

        assertEquals("safebooru.org", custom.displayName())
        assertEquals("https://safebooru.org/", custom.referer())
        assertEquals(SourcePresentationGroup.CUSTOM, fallback.group)
        assertEquals("S", fallback.mark)
        assertEquals(mapOf("Referer" to "https://safebooru.org/", "User-Agent" to "Mozilla/5.0"), custom.requestHeaders())
        assertFalse(custom in SourcePresentationCatalog.orderedSources())
    }

    @Test
    fun `installed custom sources override the fallback and follow every built-in`() {
        SourcePresentationCatalog.installCustom(
            listOf(
                customSourcePresentation(other, "Homelab", baseUrl = "https://booru.example:8443", order = 101),
                customSourcePresentation(custom, "Safebooru", "https://safebooru.org", userAgent = "Theoria/1", order = 100),
            ),
        )

        assertEquals("Safebooru", custom.displayName())
        assertEquals(mapOf("Referer" to "https://safebooru.org/", "User-Agent" to "Theoria/1"), custom.requestHeaders())
        assertEquals("https://booru.example:8443/", other.referer())
        assertEquals(listOf(custom, other), SourcePresentationCatalog.customSources())
        assertEquals(listOf(custom, other), SourcePresentationCatalog.orderedSources().takeLast(2))
        assertEquals(listOf(SourceKey.PIXIV, custom, other), listOf(other, custom, SourceKey.PIXIV).inPresentationOrder())
        assertFalse(custom in exposedRealSources(rule34XxxConfigured = true))

        SourcePresentationCatalog.installCustom(emptyList())
        assertEquals("safebooru.org", custom.displayName())
    }

    @Test
    fun `only distinct custom sources can be installed`() {
        val builtIn = SourceKey.PIXIV.presentation()
        assertTrue(runCatching { SourcePresentationCatalog.installCustom(listOf(builtIn)) }.isFailure)
        assertTrue(runCatching { customSourcePresentation(SourceKey.PIXIV, "Pixiv") }.isFailure)
        val twice = listOf(customSourcePresentation(custom, "A"), customSourcePresentation(custom, "B"))
        assertTrue(runCatching { SourcePresentationCatalog.installCustom(twice) }.isFailure)
        assertEquals(builtIn, SourceKey.PIXIV.presentation())
    }
}
