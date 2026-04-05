package com.theoriacodex.sources

import com.theoriacodex.domain.model.SourceKey
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
                SourceKey.IWARA,
                SourceKey.RULE34PAHEAL,
                SourceKey.RULE34VIDEO,
                SourceKey.RULE34GEN,
            ),
        )

        assertTrue(SourceKey.PIXIV in registry.availableSources())
        assertTrue(SourceKey.AIBOORU in registry.availableSources())
        assertTrue(SourceKey.NHENTAI in registry.availableSources())
        assertTrue(SourceKey.IWARA in registry.availableSources())
        assertTrue(SourceKey.RULE34PAHEAL in registry.availableSources())
        assertTrue(SourceKey.RULE34VIDEO in registry.availableSources())
        assertTrue(SourceKey.RULE34GEN in registry.availableSources())
    }
}
