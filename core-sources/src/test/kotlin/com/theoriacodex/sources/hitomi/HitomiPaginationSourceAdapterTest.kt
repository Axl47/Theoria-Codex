package com.theoriacodex.sources.hitomi

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import com.theoriacodex.sources.http.SourceHttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class HitomiPaginationSourceAdapterTest : HitomiSourceAdapterTestFixture() {
    @Test
    fun `blank newest range pagination has no duplicates or skipped IDs`() = runTest {
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[newest] = listOf(5, 4, 3, 2, 1)
        }
        val adapter = adapter(http, pageSize = 2)
        val query = query()

        val first = adapter.search(query, null)
        val second = adapter.search(query, requireNotNull(first.nextPageToken))
        val third = adapter.search(query, requireNotNull(second.nextPageToken))

        assertEquals(listOf("5", "4"), first.items.map { it.id.sourcePostId })
        assertEquals(listOf("3", "2"), second.items.map { it.id.sourcePostId })
        assertEquals(listOf("1"), third.items.map { it.id.sourcePostId })
        assertNull(third.nextPageToken)
        assertEquals(listOf(0L, 8L, 16L), http.binaryRequests.map { it.range.startInclusive })
        assertEquals(5, (first.items + second.items + third.items).map { it.id }.distinct().size)
    }

    @Test
    fun `legacy version two page token remains valid for Nozomi only query`() = runTest {
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[newest] = listOf(5, 4, 3)
        }
        val adapter = adapter(http, pageSize = 1)

        val first = adapter.search(query(), null)
        val legacyToken = requireNotNull(first.nextPageToken).asLegacyVersionTwoToken()
        val second = adapter.search(query(), legacyToken)

        assertEquals(listOf("5"), first.items.map { it.id.sourcePostId })
        assertEquals(listOf("4"), second.items.map { it.id.sourcePostId })
    }

    @Test
    fun `version three nonrandom token remains valid while version three random token fails closed`() = runTest {
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[newest] = listOf(5, 4, 3)
        }
        val adapter = adapter(http, pageSize = 1)

        val nonrandomFirst = adapter.search(query(), null)
        val nonrandomSecond = adapter.search(
            query(),
            requireNotNull(nonrandomFirst.nextPageToken).asPreviousVersionThreeToken(),
        )
        val randomFirst = adapter.search(query(sort = SortMode.RANDOM), null)

        assertEquals(listOf("4"), nonrandomSecond.items.map { it.id.sourcePostId })
        assertParseFailure {
            adapter.search(
                query(sort = SortMode.RANDOM),
                requireNotNull(randomFirst.nextPageToken).asPreviousVersionThreeToken(),
            )
        }
    }

    @Test
    fun `full 200 Nozomi fallback resumes by logical offset without repeats`() = runTest {
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[newest] = listOf(8, 7, 6, 5)
            fullResponseUrls += newest
        }
        val adapter = adapter(http, pageSize = 2)

        val first = adapter.search(query(), null)
        val second = adapter.search(query(), requireNotNull(first.nextPageToken))

        assertEquals(listOf("8", "7", "6", "5"), (first.items + second.items).map { it.id.sourcePostId })
        assertNull(second.nextPageToken)
        assertEquals(listOf(0L, 8L), http.binaryRequests.map { it.range.startInclusive })
    }

    @Test
    fun `sort routes map to month year and deterministic random pages`() = runTest {
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val popular = HitomiNozomi.urlFor(
            HitomiNozomiRequest(sort = HitomiNozomiSort.POPULAR_MONTH),
        )
        val top = HitomiNozomi.urlFor(
            HitomiNozomiRequest(sort = HitomiNozomiSort.POPULAR_YEAR),
        )
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[newest] = listOf(6, 5, 4, 3, 2, 1)
            binaryIndexes[popular] = listOf(30, 20, 10)
            binaryIndexes[top] = listOf(300, 200, 100)
        }
        val adapter = adapter(http, pageSize = 2)

        val popularPage = adapter.search(query(sort = SortMode.POPULAR), null)
        val topPage = adapter.search(query(sort = SortMode.TOP), null)
        val randomFirst = adapter.search(query(sort = SortMode.RANDOM), null)
        val randomRepeat = adapter.search(query(sort = SortMode.RANDOM), null)
        val randomSecond = adapter.search(
            query(sort = SortMode.RANDOM),
            requireNotNull(randomFirst.nextPageToken),
        )
        val randomThird = adapter.search(
            query(sort = SortMode.RANDOM),
            requireNotNull(randomSecond.nextPageToken),
        )

        assertEquals(listOf("30", "20"), popularPage.items.map { it.id.sourcePostId })
        assertEquals(listOf("300", "200"), topPage.items.map { it.id.sourcePostId })
        assertEquals(randomFirst.items.map { it.id }, randomRepeat.items.map { it.id })
        val randomSeed = decodeRandomSeed(requireNotNull(randomFirst.nextPageToken))
        val sourceIds = intArrayOf(1, 2, 3, 4, 5, 6)
        val permutation = HitomiDeterministicPermutation(sourceIds.size, randomSeed)
        val expectedRandomIds = sourceIds.indices.map { offset ->
            sourceIds[permutation.sourceIndex(offset.toLong())].toString()
        }
        val actualRandomIds = (randomFirst.items + randomSecond.items + randomThird.items)
            .map { post -> post.id.sourcePostId }
        assertEquals(expectedRandomIds, actualRandomIds)
        assertNull(randomThird.nextPageToken)
        val randomRequests = http.binaryRequests.filter { request -> request.url == newest }
        assertEquals(1, randomRequests.size)
        assertTrue(randomRequests.all { request -> request.range.startInclusive == 0L })
        assertTrue(randomRequests.all { request -> request.range.endInclusive == 7_999_999L })
        assertTrue(randomRequests.all { request -> request.maxBodyBytes == 8_000_000 })
        assertTrue(http.binaryRequests.any { it.url == popular })
        assertTrue(http.binaryRequests.any { it.url == top })
        assertEquals("4", decodeTokenString(requireNotNull(randomFirst.nextPageToken), "version"))
        assertEquals(
            "1",
            decodeTokenString(requireNotNull(randomFirst.nextPageToken), "randomPermutationVersion"),
        )
    }

    @Test
    fun `random pages deduplicate the primitive snapshot and retry filtered continuations exactly`() = runTest {
        val primaryUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "tag", tag = "primary", language = "all"),
        )
        val includedUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "character", tag = "keep", language = "all"),
        )
        val excludedUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "tag", tag = "skip", language = "all"),
        )
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[primaryUrl] = listOf(9, 8, 9, 7, 6, 8, 5)
            binaryIndexes[includedUrl] = listOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
            binaryIndexes[excludedUrl] = listOf(7)
        }
        val adapter = adapter(http, pageSize = 2)
        val query = query(
            include = listOf(
                SearchTerm("primary", SearchFacet.TAG, "tag"),
                SearchTerm("keep", SearchFacet.CHARACTER, "character"),
            ),
            exclude = listOf(SearchTerm("skip", SearchFacet.TAG, "tag")),
            sort = SortMode.RANDOM,
        )

        val first = adapter.search(query, null)
        val token = requireNotNull(first.nextPageToken)
        val second = adapter.search(query, token)
        val retriedSecond = adapter.search(query, token)
        val pages = buildList {
            add(first)
            add(second)
            var next = second.nextPageToken
            while (next != null) {
                val page = adapter.search(query, next)
                add(page)
                next = page.nextPageToken
            }
        }
        val ids = pages.flatMap { page -> page.items }.map { post -> post.id.sourcePostId }

        assertEquals(second.items.map(Post::id), retriedSecond.items.map(Post::id))
        assertEquals(second.nextPageToken, retriedSecond.nextPageToken)
        assertEquals(4, ids.size)
        assertEquals(4, ids.toSet().size)
        assertTrue("7" !in ids)
    }

    @Test
    fun `different random seeds reuse one raw snapshot for the same provider URL`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(9, 8, 7, 6)
        }
        val adapter = adapter(http, pageSize = 1)
        val first = adapter.search(query(sort = SortMode.RANDOM), null)
        val second = adapter.search(query(sort = SortMode.RANDOM).copy(minScore = 1), null)

        assertTrue(
            decodeRandomSeed(requireNotNull(first.nextPageToken)) !=
                decodeRandomSeed(requireNotNull(second.nextPageToken)),
        )
        assertEquals(1, http.binaryRequests.count { request -> request.url == all })
        assertEquals(1, adapter.cacheSnapshot().random.keysInLruOrder.size)
    }

    @Test
    fun `all retained Hitomi search caches expose finite byte budgets`() = runTest {
        val snapshot = adapter(RoutingHitomiHttpClient()).cacheSnapshot()

        assertEquals(HitomiGlobalIndexCache.DEFAULT_MAX_BYTES, snapshot.globalIndex.maxBytes)
        assertEquals(HitomiSourceAdapter.DEFAULT_MEMBERSHIP_CACHE_BYTES, snapshot.membership.maxBytes)
        assertEquals(HitomiRandomSnapshotCache.DEFAULT_MAX_BYTES, snapshot.random.maxBytes)
        assertEquals(HitomiSourceAdapter.DEFAULT_KNOWN_SIZE_CACHE_BYTES, snapshot.knownSizes.maxBytes)
        assertEquals(
            HitomiSourceAdapter.DEFAULT_SUGGESTION_COUNT_CACHE_BYTES,
            snapshot.suggestionCounts.maxBytes,
        )
    }

    @Test
    fun `interleaved random queries resume their exact cached snapshots`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val english = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "english"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(8, 7, 6, 5)
            binaryIndexes[english] = listOf(4, 3, 2, 1)
        }
        val adapter = adapter(http, pageSize = 1)
        val allQuery = query(sort = SortMode.RANDOM)
        val englishQuery = randomLanguageQuery("english")

        val allFirst = adapter.search(allQuery, null)
        adapter.search(englishQuery, null)
        val allSecond = adapter.search(allQuery, requireNotNull(allFirst.nextPageToken))

        assertTrue(allFirst.items.single().id != allSecond.items.single().id)
        assertEquals(1, http.binaryRequests.count { request -> request.url == all })
        assertEquals(1, http.binaryRequests.count { request -> request.url == english })
    }

    @Test
    fun `random cache miss refetches and resumes when snapshot digest is unchanged`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val english = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "english"))
        val japanese = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "japanese"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(9, 8, 7, 6)
            binaryIndexes[english] = listOf(5, 4, 3)
            binaryIndexes[japanese] = listOf(2, 1)
        }
        val adapter = adapter(http, pageSize = 1, randomSnapshotCacheMaxBytes = 300L)
        val allQuery = query(sort = SortMode.RANDOM)
        val first = adapter.search(allQuery, null)
        adapter.search(randomLanguageQuery("english"), null)
        adapter.search(randomLanguageQuery("japanese"), null)

        val resumed = adapter.search(allQuery, requireNotNull(first.nextPageToken))

        assertTrue(first.items.single().id != resumed.items.single().id)
        assertEquals(2, http.binaryRequests.count { request -> request.url == all })
    }

    @Test
    fun `random cache miss fails closed when provider snapshot changed`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val english = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "english"))
        val japanese = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "japanese"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(9, 8, 7, 6)
            binaryIndexes[english] = listOf(5, 4, 3)
            binaryIndexes[japanese] = listOf(2, 1)
        }
        val adapter = adapter(http, pageSize = 1, randomSnapshotCacheMaxBytes = 300L)
        val allQuery = query(sort = SortMode.RANDOM)
        val first = adapter.search(allQuery, null)
        adapter.search(randomLanguageQuery("english"), null)
        adapter.search(randomLanguageQuery("japanese"), null)
        http.binaryIndexes[all] = listOf(10, 9, 8, 7)

        assertParseFailure {
            adapter.search(allQuery, requireNotNull(first.nextPageToken))
        }
        assertEquals(2, http.binaryRequests.count { request -> request.url == all })
    }

    @Test
    fun `continuation joining an in flight changed snapshot fails through public parse contract`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val english = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "english"))
        val japanese = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "japanese"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(9, 8, 7, 6)
            binaryIndexes[english] = listOf(5, 4, 3)
            binaryIndexes[japanese] = listOf(2, 1)
        }
        val adapter = adapter(http, pageSize = 1, randomSnapshotCacheMaxBytes = 300L)
        val originalQuery = query(sort = SortMode.RANDOM)
        val first = adapter.search(originalQuery, null)
        adapter.search(randomLanguageQuery("english"), null)
        adapter.search(randomLanguageQuery("japanese"), null)

        http.binaryIndexes[all] = listOf(10, 9, 8, 7)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        http.binaryRequestStarted[all] = loadStarted
        http.binaryRequestRelease[all] = releaseLoad
        val freshWalk = async {
            adapter.search(originalQuery.copy(minScore = 1), null)
        }
        loadStarted.await()
        val continuation = async {
            runCatching {
                adapter.search(originalQuery, requireNotNull(first.nextPageToken))
            }.exceptionOrNull()
        }
        runCurrent()
        releaseLoad.complete(Unit)

        freshWalk.await()
        val failure = continuation.await()
        assertTrue(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
        assertEquals(2, http.binaryRequests.count { request -> request.url == all })
    }

}
