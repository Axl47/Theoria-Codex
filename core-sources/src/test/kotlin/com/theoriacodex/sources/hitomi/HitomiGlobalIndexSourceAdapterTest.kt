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
internal class HitomiGlobalIndexSourceAdapterTest : HitomiSourceAdapterTestFixture() {
    @Test
    fun `All search reads the provider global galleries index without classifying as a tag`() = runTest {
        val version = "12345"
        val indexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"
        val dataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes["https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"] = version
            rawFullBodies[indexUrl] = globalIndexNode("girl", dataLength = 12)
            rawFullBodies[dataUrl] = globalGalleryRecord(listOf(4, 3))
        }
        val adapter = adapter(http, pageSize = 2)

        val page = adapter.search(query(include = listOf(SearchTerm("girl"))), null)

        assertEquals(listOf("4", "3"), page.items.map { post -> post.id.sourcePostId })
        assertEquals(listOf(indexUrl, dataUrl), http.binaryRequests.map { request -> request.url })
        assertFalse(http.binaryRequests.any { request -> request.url.endsWith("/tag/girl-all.nozomi") })
    }

    @Test
    fun `All search removes duplicate gallery ids from the provider global index`() = runTest {
        val version = "12345"
        val indexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"
        val dataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes["https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"] = version
            rawFullBodies[indexUrl] = globalIndexNode("girl", dataLength = 20)
            rawFullBodies[dataUrl] = globalGalleryRecord(listOf(4, 3, 4, 2))
        }
        val adapter = adapter(http, pageSize = 4)

        val page = adapter.search(query(include = listOf(SearchTerm("girl"))), null)

        assertEquals(listOf("4", "3", "2"), page.items.map { post -> post.id.sourcePostId })
        assertEquals(page.items.size, page.items.map { post -> post.id }.distinct().size)
    }

    @Test
    fun `typed character search removes duplicate gallery ids from Nozomi`() = runTest {
        val characterUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "character", tag = "klee", language = "all"),
        )
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[characterUrl] = listOf(4_076_681, 4_076_680, 4_076_681)
            galleryBodies[4_076_680] = galleryWithFiles(
                id = 4_076_681,
                files = """[{"name":"1.jpg","hash":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","width":800,"height":1200,"hasavif":1}]""",
            )
        }
        val adapter = adapter(http, pageSize = 3)

        val page = adapter.search(
            query(
                include = listOf(
                    SearchTerm("klee", SearchFacet.CHARACTER, "character"),
                ),
            ),
            null,
        )

        assertEquals(listOf("4076681"), page.items.map { post -> post.id.sourcePostId })
        assertEquals(page.items.size, page.items.map { post -> post.id }.distinct().size)
    }

    @Test
    fun `global search cache switches atomically to the current galleries index version`() = runTest {
        val versionUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        val v1IndexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.100.index"
        val v1DataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.100.data"
        val v2IndexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.200.index"
        val v2DataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.200.data"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[versionUrl] = "100"
            rawFullBodies[v1IndexUrl] = globalIndexNode("girl", dataLength = 8)
            rawFullBodies[v1DataUrl] = globalGalleryRecord(listOf(4))
            rawFullBodies[v2IndexUrl] = globalIndexNode("girl", dataLength = 8)
            rawFullBodies[v2DataUrl] = globalGalleryRecord(listOf(9))
        }
        val adapter = adapter(http, pageSize = 1)

        val first = adapter.search(query(include = listOf(SearchTerm("girl"))), null)
        http.textRoutes[versionUrl] = "200"
        val second = adapter.search(query(include = listOf(SearchTerm("girl"))), null)

        assertEquals(listOf("4"), first.items.map { post -> post.id.sourcePostId })
        assertEquals(listOf("9"), second.items.map { post -> post.id.sourcePostId })
        assertEquals(
            listOf(v1IndexUrl, v1DataUrl, v2IndexUrl, v2DataUrl),
            http.binaryRequests.map { request -> request.url },
        )
    }

    @Test
    fun `same global term and version reuse the cached gallery ids`() = runTest {
        val version = "12345"
        val versionUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        val indexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"
        val dataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[versionUrl] = version
            rawFullBodies[indexUrl] = globalIndexNode("girl", dataLength = 8)
            rawFullBodies[dataUrl] = globalGalleryRecord(listOf(4))
        }
        val adapter = adapter(http, pageSize = 1)

        adapter.search(query(include = listOf(SearchTerm("girl"))), null)
        adapter.search(query(include = listOf(SearchTerm("girl"))), null)

        assertEquals(listOf(indexUrl, dataUrl), http.binaryRequests.map { request -> request.url })
        assertEquals(2, http.textRequests.count { request -> request.url == versionUrl })
    }

    @Test
    fun `global search retains loaded ids when one record exceeds an injected cache budget`() = runTest {
        val version = "12345"
        val indexUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"
        val dataUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes["https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"] = version
            rawFullBodies[indexUrl] = globalIndexNode("girl", dataLength = 12)
            rawFullBodies[dataUrl] = globalGalleryRecord(listOf(4, 3))
        }
        val adapter = adapter(
            http = http,
            pageSize = 2,
            globalIndexCacheMaxBytes = Int.SIZE_BYTES.toLong(),
        )

        val page = adapter.search(query(include = listOf(SearchTerm("girl"))), null)

        assertEquals(listOf("4", "3"), page.items.map { post -> post.id.sourcePostId })
    }

    @Test
    fun `global primary page token fails closed when galleries index version rotates`() = runTest {
        val versionUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[versionUrl] = "100"
            routeGlobalIndex(version = "100", term = "girl", ids = listOf(6, 5, 4))
            routeGlobalIndex(version = "200", term = "girl", ids = listOf(9, 8, 7))
        }
        val adapter = adapter(http, pageSize = 1)
        val query = query(include = listOf(SearchTerm("girl")))

        val first = adapter.search(query, null)
        val token = requireNotNull(first.nextPageToken)
        assertEquals("100", decodeTokenString(token, "globalIndexVersion"))
        assertEquals("TAG|tag|girl", decodeTokenString(token, "primaryKey"))
        assertParseFailure {
            adapter.search(query, token.asLegacyVersionTwoToken())
        }

        http.textRoutes[versionUrl] = "200"

        assertParseFailure {
            adapter.search(query, token)
        }
    }

    @Test
    fun `global secondary page token fails closed when galleries index version rotates`() = runTest {
        val versionUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        val artistUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "artist", tag = "najar", language = "all"),
        )
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[versionUrl] = "100"
            binaryIndexes[artistUrl] = listOf(6, 5, 4)
            routeGlobalIndex(version = "100", term = "girl", ids = listOf(6, 5, 4, 3))
            routeGlobalIndex(version = "200", term = "girl", ids = listOf(6, 5, 3, 2))
        }
        val adapter = adapter(http, pageSize = 1)
        val query = query(
            include = listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("girl"),
            ),
        )

        val first = adapter.search(query, null)
        val token = requireNotNull(first.nextPageToken)
        assertEquals("ARTIST|artist|najar", decodeTokenString(token, "primaryKey"))
        assertEquals("100", decodeTokenString(token, "globalIndexVersion"))

        http.textRoutes[versionUrl] = "200"

        assertParseFailure {
            adapter.search(query, token)
        }
    }

    @Test
    fun `global exclusion page token fails closed when galleries index version rotates`() = runTest {
        val versionUrl = "https://ltn.gold-usergeneratedcontent.net/galleriesindex/version"
        val newest = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[versionUrl] = "100"
            binaryIndexes[newest] = listOf(6, 5, 4, 3)
            routeGlobalIndex(version = "100", term = "girl", ids = listOf(2))
            routeGlobalIndex(version = "200", term = "girl", ids = listOf(5))
        }
        val adapter = adapter(http, pageSize = 1)
        val query = query(exclude = listOf(SearchTerm("girl")))

        val first = adapter.search(query, null)
        val token = requireNotNull(first.nextPageToken)
        assertEquals("__all__", decodeTokenString(token, "primaryKey"))
        assertEquals("100", decodeTokenString(token, "globalIndexVersion"))

        http.textRoutes[versionUrl] = "200"

        assertParseFailure {
            adapter.search(query, token)
        }
    }

}
