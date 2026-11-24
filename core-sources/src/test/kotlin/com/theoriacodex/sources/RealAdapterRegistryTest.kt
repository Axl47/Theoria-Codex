package com.theoriacodex.sources

import com.theoriacodex.domain.adapter.FileHashLookupSourceAdapter
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.ProtocolFamily
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.domain.source.SiteAddressParse
import com.theoriacodex.domain.source.SiteProfile
import com.theoriacodex.sources.custom.SiteFamilyRegistry
import com.theoriacodex.sources.custom.SiteRequestContext
import com.theoriacodex.sources.hitomi.HitomiSourceAdapter
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealAdapterRegistryTest {
    @Test
    fun `default exposure only includes pixiv`() {
        val registry = RealAdapterRegistry(
            credentialsProvider = FakeCredentialsProvider(),
            httpClient = FakeHttpClient(),
        )

        assertEquals(setOf(SourceKey.PIXIV), registry.availableSources())
        assertNull(registry.adapterFor(SourceKey.AIBOORU))
        assertNull(registry.adapterFor(SourceKey.GELBOORU))
        assertNull(registry.adapterFor(SourceKey.HITOMI))
        assertNull(registry.adapterFor(SourceKey.IWARA))
        assertNull(registry.adapterFor(SourceKey.NHENTAI))
        assertNull(registry.adapterFor(SourceKey.RULE34PAHEAL))
    }

    @Test
    fun `registry can expose additional sources for later phases`() {
        val registry = RealAdapterRegistry(
            credentialsProvider = FakeCredentialsProvider(),
            httpClient = FakeHttpClient(),
            exposedSources = setOf(
                SourceKey.PIXIV,
                SourceKey.AIBOORU,
                SourceKey.NHENTAI,
                SourceKey.HITOMI,
                SourceKey.IWARA,
                SourceKey.RULE34PAHEAL,
                SourceKey.RULE34VIDEO,
                SourceKey.RULE34GEN,
            ),
        )

        assertTrue(SourceKey.PIXIV in registry.availableSources())
        assertTrue(SourceKey.AIBOORU in registry.availableSources())
        assertTrue(SourceKey.NHENTAI in registry.availableSources())
        assertTrue(SourceKey.HITOMI in registry.availableSources())
        assertTrue(registry.adapterFor(SourceKey.HITOMI) is HitomiSourceAdapter)
        assertTrue(SourceKey.IWARA in registry.availableSources())
        assertTrue(SourceKey.RULE34PAHEAL in registry.availableSources())
        assertTrue(SourceKey.RULE34VIDEO in registry.availableSources())
        assertTrue(SourceKey.RULE34GEN in registry.availableSources())
    }

    @Test
    fun `only the families with an md5 search look up files by hash (ADR 0008)`() {
        val http = FakeHttpClient()
        val registry = RealAdapterRegistry(FakeCredentialsProvider(), http, exposedSources = SourceKey.builtIns.toSet())
        val address = (SiteAddress.parse("booru.example") as SiteAddressParse.Valid).address
        val customFamilies = SiteFamilyRegistry.default().families.associate { siteFamily ->
            val profile = SiteProfile(address.derivedSourceId, address, "Booru", siteFamily.family.name, addedAtEpochMs = 0)
            siteFamily.family to siteFamily.createAdapter(profile, SourceKey.of(profile), SiteRequestContext(http))
        }

        assertEquals(SourceKey.builtIns.toSet(), registry.availableSources())
        assertEquals(
            setOf(SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.RULE34XXX),
            SourceKey.builtIns.filter { registry.adapterFor(it) is FileHashLookupSourceAdapter }.toSet(),
        )
        assertEquals(setOf(ProtocolFamily.DANBOORU, ProtocolFamily.GELBOORU_02), customFamilies.filterValues { it is FileHashLookupSourceAdapter }.keys)
    }
}
