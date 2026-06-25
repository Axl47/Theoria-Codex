package com.theoriacodex.stubs

import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StubProviderContractTest {
    @Test
    fun `every stub source satisfies search identity media tag and paging contracts`() = runBlocking {
        val registry = StubAdapterRegistry(runtime = StubRuntime(StubScenarioPreset.NORMAL))

        SourceKey.entries.forEach { source ->
            val adapter = requireNotNull(registry.adapterFor(source)) { "Missing adapter for $source" }
            val firstPage = adapter.search(sampleQuery(source), pageToken = null)

            assertTrue("Expected first-page items for $source", firstPage.items.isNotEmpty())
            assertEquals("page_2", firstPage.nextPageToken)
            firstPage.items.forEach { post ->
                assertPostContract(source, post)
            }

            val secondPage = adapter.search(sampleQuery(source), pageToken = firstPage.nextPageToken)
            assertTrue("Expected second-page items for $source", secondPage.items.isNotEmpty())
            assertNull(secondPage.nextPageToken)
            secondPage.items.forEach { post ->
                assertPostContract(source, post)
            }
        }
    }

    @Test
    fun `every stub source resolves own ids and ignores foreign ids`() = runBlocking {
        val registry = StubAdapterRegistry(runtime = StubRuntime(StubScenarioPreset.NORMAL))

        SourceKey.entries.forEach { source ->
            val adapter = requireNotNull(registry.adapterFor(source))
            val firstPost = adapter.search(sampleQuery(source), pageToken = null).items.first()

            val resolved = adapter.resolvePost(firstPost.id)
            assertNotNull("Expected resolvePost to find ${firstPost.id}", resolved)
            assertEquals(firstPost.id, resolved?.id)
            assertNull(adapter.resolvePost(PostId(source = foreignSource(source), sourcePostId = firstPost.id.sourcePostId)))
        }
    }

    @Test
    fun `every stub source exposes shaped trending autocomplete and quick queries`() = runBlocking {
        val registry = StubAdapterRegistry(runtime = StubRuntime(StubScenarioPreset.NORMAL))

        SourceKey.entries.forEach { source ->
            val adapter = requireNotNull(registry.adapterFor(source))
            val trending = adapter.trendingTags(limit = 3)
            assertTrue("Expected trending tags for $source", trending.isNotEmpty())
            trending.forEach { tag ->
                assertTrue(tag.text.isNotBlank())
                assertNotNull(tag.type)
            }

            val prefix = trending.first().text.take(3)
            val autocomplete = adapter.autocompleteTags(prefix = prefix, limit = 3)
            assertTrue("Expected autocomplete tags for $source and prefix $prefix", autocomplete.isNotEmpty())
            autocomplete.forEach { tag ->
                assertTrue(tag.text.contains(prefix, ignoreCase = true))
            }

            QuickQueryKind.entries.forEach { kind ->
                val query = adapter.quickQuery(kind)
                assertEquals(QueryMode.Source(source), query.mode)
                if (kind == QuickQueryKind.RANDOM) {
                    assertNotNull(query.sort)
                }
            }
        }
    }

    @Test
    fun `partial failure stubs surface typed network failures`() = runBlocking {
        val registry = StubAdapterRegistry(runtime = StubRuntime(StubScenarioPreset.PARTIAL_FAILURE))
        val adapter = requireNotNull(registry.adapterFor(SourceKey.GELBOORU))

        val failure = runCatching {
            adapter.search(sampleQuery(SourceKey.GELBOORU), pageToken = null)
        }.exceptionOrNull()

        assertTrue(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.NETWORK, (failure as SourceAdapterException).reason)
    }

    private fun assertPostContract(source: SourceKey, post: Post) {
        assertEquals(source, post.id.source)
        assertTrue("sourcePostId must be stable for ${post.id}", post.id.sourcePostId.isNotBlank())
        assertTrue("preview URL required for ${post.id}", !post.preview.url.isNullOrBlank())
        assertTrue("media URL required for ${post.id}", post.hasAnyMediaUrl())
        assertFalse("canonical tags required for ${post.id}", post.canonicalTags.isEmpty())
        assertFalse("raw tags required for ${post.id}", post.rawTags.isEmpty())
    }

    private fun Post.hasAnyMediaUrl(): Boolean {
        return sequenceOf(
            preview.url,
            full?.url,
            media.firstOrNull { !it.url.isNullOrBlank() }?.url,
        ).any { !it.isNullOrBlank() }
    }

    private fun foreignSource(source: SourceKey): SourceKey {
        return SourceKey.entries.first { it != source }
    }

    private fun sampleQuery(source: SourceKey): Query {
        return Query(
            mode = QueryMode.Source(source),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
