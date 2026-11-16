package com.theoriacodex.sources

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import com.theoriacodex.sources.testing.ProviderPageFixture
import com.theoriacodex.sources.testing.providerPageCredentials
import com.theoriacodex.sources.testing.providerPageFixtures
import com.theoriacodex.sources.testing.providerPageQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealProviderPageContractTest {
    @Test
    fun `real provider pages deduplicate canonical identities after parsing without losing continuation or order`() = runTest {
        for (fixture in providerPageFixtures()) {
            val adapter = registry(fixture).adapterFor(fixture.source)!!
            val page = adapter.search(providerPageQuery(fixture.source), null)
            assertEquals(fixture.source.id, listOf("42", "43"), page.items.map { it.id.sourcePostId })
            assertEquals(fixture.source.id, fixture.continuation, page.nextPageToken)
            assertEquals(fixture.source.id, setOf(fixture.source), page.items.map { it.id.source }.toSet())
        }
    }

    @Test
    fun `creator pages retain canonical uniqueness and authoritative continuation`() = runTest {
        for (fixture in providerPageFixtures().filter { it.source in setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.IWARA) }) {
            val adapter = registry(fixture).adapterFor(fixture.source) as CreatorPostsSourceAdapter
            val page = adapter.searchCreatorPosts(CreatorProfile(
                source = fixture.source, displayName = "Creator", profileId = "7", uploadsQuery = "7", profileUrl = null,
            ), null)
            assertEquals(fixture.source.id, listOf("42", "43"), page.items.map { it.id.sourcePostId })
            assertEquals(fixture.source.id, fixture.continuation, page.nextPageToken)
        }
    }

    private fun registry(fixture: ProviderPageFixture) = RealAdapterRegistry(
        credentialsProvider = providerPageCredentials(),
        httpClient = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fixture.body) },
        exposedSources = setOf(fixture.source),
    )
}
