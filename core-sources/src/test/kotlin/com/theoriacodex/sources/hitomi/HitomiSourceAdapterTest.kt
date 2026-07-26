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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiSourceAdapterTest {
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

    @Test
    fun `featured closed facets expose discoverable type and language values`() = runTest {
        val adapter = adapter(RoutingHitomiHttpClient())

        val types = adapter.featuredFacetedSuggestions(
            FacetedSearchScope(SearchFacet.TYPE, "type"),
            limit = 20,
        )
        val languages = adapter.featuredFacetedSuggestions(
            FacetedSearchScope(SearchFacet.LANGUAGE, "language"),
            limit = 3,
        )

        assertEquals(
            listOf("doujinshi", "manga", "artistcg", "gamecg", "imageset", "anime"),
            types.map { suggestion -> suggestion.text },
        )
        assertEquals(
            listOf("japanese", "english", "chinese"),
            languages.map { suggestion -> suggestion.text },
        )
        assertTrue(types.all { it.facet == SearchFacet.TYPE && it.sourceNamespace == "type" })
    }

    @Test
    fun `autocomplete preserves global and scoped Hitomi taxonomy`() = runTest {
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[HitomiProtocol.autocompleteUrl("global", "tag")] = fixture("global-tags.json")
            textRoutes[HitomiProtocol.autocompleteUrl("artist", "kio")] = fixture("artist-kio.json")
        }
        val adapter = adapter(http)

        val global = adapter.autocompleteFaceted("tag", FacetedSearchScope.All, limit = 20)
        val artists = adapter.autocompleteFaceted(
            "kio",
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            limit = 4,
        )

        assertEquals(10, global.size)
        assertTrue(global.any { it.text == "frottage" && it.sourceNamespace == "female" })
        assertTrue(global.any { it.text == "tagame gengoroh" && it.facet == SearchFacet.ARTIST })
        assertEquals(4, artists.size)
        assertTrue(artists.all { it.facet == SearchFacet.ARTIST && it.sourceNamespace == "artist" })
        assertTrue(http.textRequests.all { it.headers["Referer"] == "https://hitomi.la/" })
        assertTrue(http.textRequests.all { it.headers["User-Agent"] == "Mozilla/5.0" })
    }

    @Test
    fun `media 404 recovery refreshes config then orders the alternate shard as fallback`() = runTest {
        val staleConfig = fixture("gg-shape.js")
        val refreshedConfig = staleConfig.replace("1783681201/", "1783689999/")
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[HitomiMediaUrlResolver.GG_CONFIGURATION_URL] = staleConfig
        }
        val resolver = HitomiMediaUrlResolver(http)
        val file = HitomiMediaFile(
            hash = "2733fb24ac9ec065e6af94adb500554e56093df4481a92e85425955f368fa7f1",
            name = "01.webp",
            hasAvif = true,
        )
        val staleCandidates = resolver.candidates(file)
        val staleWebp = staleCandidates.first { candidate ->
            candidate.format == HitomiMediaFormat.WEBP && !candidate.isAlternateShard
        }
        val staleRef = ImageRef(
            url = staleWebp.url,
            localPath = null,
            mime = staleWebp.mime,
            progressiveUrls = staleCandidates.map(HitomiMediaCandidate::url),
        )
        val staleAvif = staleCandidates.first { candidate ->
            candidate.format == HitomiMediaFormat.AVIF && !candidate.isAlternateShard
        }
        val staleFullRef = staleRef.copy(url = staleAvif.url, mime = staleAvif.mime)
        val post = Post(
            id = PostId(SourceKey.HITOMI, "4042375"),
            preview = staleRef,
            full = staleFullRef,
            media = listOf(staleFullRef),
            pageUrl = "https://hitomi.la/cg/example-4042375.html",
            width = 1080,
            height = 1920,
            canonicalTags = listOf("animated"),
            rawTags = listOf("animated"),
            authorName = "najar",
            createdAtEpochMs = null,
        )
        http.textRoutes[HitomiMediaUrlResolver.GG_CONFIGURATION_URL] = refreshedConfig
        val adapter = HitomiSourceAdapter(httpClient = http, mediaUrlResolver = resolver)

        val recovered = requireNotNull(adapter.recoverPostMedia(post, staleRef))
        val refreshedWebpUrls = recovered.preview.progressiveUrls.take(2)
        val secondRecovery = requireNotNull(adapter.recoverPostMedia(recovered, staleRef))

        assertTrue(refreshedWebpUrls[0].startsWith("https://w1.gold-usergeneratedcontent.net/1783689999/"))
        assertTrue(refreshedWebpUrls[1].startsWith("https://w2.gold-usergeneratedcontent.net/1783689999/"))
        assertTrue(recovered.full?.url.orEmpty().startsWith("https://a1.gold-usergeneratedcontent.net/1783689999/"))
        assertTrue(
            recovered.full?.progressiveUrls?.getOrNull(1).orEmpty()
                .startsWith("https://a2.gold-usergeneratedcontent.net/1783689999/"),
        )
        assertEquals(refreshedWebpUrls, secondRecovery.preview.progressiveUrls.take(2))
        assertEquals(
            2,
            http.textRequests.count { request ->
                request.url == HitomiMediaUrlResolver.GG_CONFIGURATION_URL
            },
        )
    }

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
        val expectedRandomIds = intArrayOf(6, 5, 4, 3, 2, 1).apply {
            val random = Random(randomSeed)
            for (index in lastIndex downTo 1) {
                val swapIndex = random.nextInt(index + 1)
                val value = this[index]
                this[index] = this[swapIndex]
                this[swapIndex] = value
            }
        }.map(Int::toString)
        val actualRandomIds = (randomFirst.items + randomSecond.items + randomThird.items)
            .map { post -> post.id.sourcePostId }
        assertEquals(expectedRandomIds, actualRandomIds)
        assertNull(randomThird.nextPageToken)
        val randomRequests = http.binaryRequests.filter { request -> request.url == newest }
        assertEquals(2, randomRequests.size)
        assertTrue(randomRequests.all { request -> request.range.startInclusive == 0L })
        assertTrue(randomRequests.all { request -> request.range.endInclusive == 7_999_999L })
        assertTrue(randomRequests.all { request -> request.maxBodyBytes == 8_000_000 })
        assertTrue(http.binaryRequests.any { it.url == popular })
        assertTrue(http.binaryRequests.any { it.url == top })
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
        val adapter = adapter(http, pageSize = 1)
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
        val adapter = adapter(http, pageSize = 1)
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
    fun `smallest typed include drives popular range intersection and exclusions`() = runTest {
        val artistAutocomplete = HitomiProtocol.autocompleteUrl("artist", "najar")
        val typeAutocomplete = HitomiProtocol.autocompleteUrl("type", "artistcg")
        val typePopular = HitomiNozomi.urlFor(
            HitomiNozomiRequest(
                area = "type",
                tag = "artistcg",
                language = "english",
                sort = HitomiNozomiSort.POPULAR_MONTH,
            ),
        )
        val artistNewest = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "artist", tag = "najar", language = "english"),
        )
        val excludedFemale = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "tag", tag = "female:x-ray", language = "english"),
        )
        val http = RoutingHitomiHttpClient().apply {
            textRoutes[artistAutocomplete] = "[[\"najar\",100,\"artist\"]]"
            textRoutes[typeAutocomplete] = "[[\"artistcg\",2,\"type\"]]"
            binaryIndexes[typePopular] = listOf(4, 3)
            binaryIndexes[artistNewest] = listOf(5, 4, 3, 2)
            binaryIndexes[excludedFemale] = listOf(3)
        }
        val adapter = adapter(http, pageSize = 3)
        adapter.autocompleteFaceted(
            "najar",
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            10,
        )
        adapter.autocompleteFaceted(
            "artistcg",
            FacetedSearchScope(SearchFacet.TYPE, "type"),
            10,
        )
        val query = query(
            include = listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("artistcg", SearchFacet.TYPE, "type"),
                SearchTerm("english", SearchFacet.LANGUAGE, "language"),
            ),
            exclude = listOf(SearchTerm("x-ray", SearchFacet.TAG, "female")),
            sort = SortMode.POPULAR,
        )

        val page = adapter.search(query, null)

        assertEquals(listOf("4"), page.items.map { it.id.sourcePostId })
        assertEquals(typePopular, http.binaryRequests.last().url)
        assertTrue(http.binaryRequests.any { it.url == artistNewest })
        assertTrue(http.binaryRequests.any { it.url == excludedFemale })
        assertNull(page.nextPageToken)
    }

    @Test
    fun `negative language is subtracted as an exact membership list`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val english = HitomiNozomi.urlFor(HitomiNozomiRequest(language = "english"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(4, 3, 2)
            binaryIndexes[english] = listOf(3, 2)
        }
        val adapter = adapter(http, pageSize = 3)

        val page = adapter.search(
            query(exclude = listOf(SearchTerm("english", SearchFacet.LANGUAGE, "language"))),
            null,
        )

        assertEquals(listOf("4"), page.items.map { it.id.sourcePostId })
        assertTrue(http.binaryRequests.any { it.url == english })
    }

    @Test
    fun `search hydration is bounded ordered isolated and sparse while resolve is complete`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val mediaCalls = AtomicInteger(0)
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(4_042_375, 999, 7_231)
            galleryBodies[4_042_375] = fixture("gallery-4042375.js")
            galleryBodies[999] = "var galleryinfo = {broken};"
            galleryBodies[7_231] = fixture("gallery-7231.js")
            galleryDelayMs = 50L
        }
        val adapter = adapter(
            http = http,
            pageSize = 3,
            hydrationConcurrency = 2,
            mediaCalls = mediaCalls,
        )

        val cards = adapter.search(query(), null).items

        assertEquals(listOf("4042375", "7231"), cards.map { it.id.sourcePostId })
        assertEquals(2, http.maxGalleryConcurrency.get())
        val animatedCard = cards.first()
        assertEquals("Hana 12/2024 webp Animated", animatedCard.title)
        assertEquals("najar", animatedCard.authorName)
        assertEquals("najar", animatedCard.creatorProfile?.profileId)
        assertEquals("artist:najar", animatedCard.creatorProfile?.uploadsQuery)
        assertEquals("https://hitomi.la/artist/najar-all.html", animatedCard.creatorProfile?.profileUrl)
        assertEquals(1, animatedCard.creatorProfiles.size)
        assertEquals(44, animatedCard.mediaCount)
        assertTrue(animatedCard.media.isEmpty())
        assertNull(animatedCard.full)
        assertTrue(animatedCard.preview.url?.contains("/webp/") == true)
        assertTrue(animatedCard.preview.isAnimated)
        assertTrue(animatedCard.taxonomy.any { it.facet == SearchFacet.SERIES && it.value == "the idolmaster" })
        assertTrue(animatedCard.taxonomy.any { it.facet == SearchFacet.TAG && it.sourceNamespace == "female" })
        assertEquals(
            OffsetDateTime.parse("2026-07-09T23:41:00-05:00").toInstant().toEpochMilli(),
            animatedCard.createdAtEpochMs,
        )
        assertEquals(1, cards.last().mediaCount)
        assertNull(cards.last().creatorProfile)
        assertTrue(cards.last().creatorProfiles.isEmpty())
        assertEquals(2, mediaCalls.get())

        val resolved = requireNotNull(adapter.resolvePost(PostId(SourceKey.HITOMI, "4042375")))
        assertEquals(44, resolved.media.size)
        assertEquals(44, resolved.mediaCount)
        assertTrue(resolved.preview.url?.contains("/webp/") == true)
        assertTrue(resolved.full?.url?.contains("/webp/") == true)
        assertEquals("image/webp", resolved.full?.mime)
        assertTrue(resolved.media.all { it.isAnimated })
        assertTrue(resolved.media.all { it.url?.contains("/webp/") == true })
        assertTrue(resolved.media.all { media ->
            media.progressiveUrls.firstOrNull()?.contains("/webp/") == true &&
                media.progressiveUrls.any { it.contains("/avif/") } &&
                media.progressiveUrls.any { it.contains("/original/") }
        })
        assertEquals(46, mediaCalls.get())

        val anime = requireNotNull(adapter.resolvePost(PostId(SourceKey.HITOMI, "7231")))
        assertEquals(1, anime.media.size)
        assertEquals("video/mp4", anime.media.single().mime)
        assertTrue(anime.media.single().url?.contains("/videos/") == true)
        assertEquals(1, anime.mediaCount)
        assertEquals(47, mediaCalls.get())
        assertNull(anime.creatorProfile)
        assertTrue(anime.creatorProfiles.isEmpty())
        assertNull(anime.authorName)
    }

    @Test
    fun `gallery maps every artist to canonical creator identity and encoded URL`() = runTest {
        val http = RoutingHitomiHttpClient().apply {
            galleryBodies[55] = galleryWithArtists(
                id = 55,
                artists = """[
                    {"artist":"Arisue Tsukasa","url":"/artist/arisue%20tsukasa-all.html"},
                    {"artist":"Artist & Co","url":"/artist/wrong-all.html"},
                    {"artist":"A/B","url":"/artist/a%2Fb-all.html"}
                ]""".trimIndent(),
            )
        }
        val post = requireNotNull(adapter(http).resolvePost(PostId(SourceKey.HITOMI, "55")))

        assertEquals(2, post.creatorProfiles.size)
        assertEquals(post.creatorProfiles.first(), post.creatorProfile)
        assertEquals("Arisue Tsukasa", post.authorName)
        assertEquals(
            CreatorProfile(
                source = SourceKey.HITOMI,
                displayName = "Arisue Tsukasa",
                profileId = "arisue tsukasa",
                profileUrl = "https://hitomi.la/artist/arisue%20tsukasa-all.html",
                uploadsQuery = "artist:arisue tsukasa",
            ),
            post.creatorProfiles[0],
        )
        assertEquals("artist & co", post.creatorProfiles[1].profileId)
        assertEquals("artist:artist & co", post.creatorProfiles[1].uploadsQuery)
        assertEquals(
            "https://hitomi.la/artist/artist%20%26%20co-all.html",
            post.creatorProfiles[1].profileUrl,
        )
    }

    @Test
    fun `gallery creator identities count code points and reject malformed unicode`() = runTest {
        val supplementaryCharacter = "\uD83D\uDE00"
        val maximumIdentity = supplementaryCharacter.repeat(256)
        val overLimitIdentity = supplementaryCharacter.repeat(257)
        val replacementIdentity = "replacement \uFFFD"
        val gson = Gson()
        val http = RoutingHitomiHttpClient().apply {
            galleryBodies[56] = galleryWithArtists(
                id = 56,
                artists = """[
                    {"artist":${gson.toJson(maximumIdentity)}},
                    {"artist":${gson.toJson(overLimitIdentity)}},
                    {"artist":"\uD800"},
                    {"artist":"\u000Acontrol"},
                    {"artist":${gson.toJson(replacementIdentity)},"url":"/artist/replacement%20%FF-all.html"}
                ]""".trimIndent(),
            )
        }

        val post = requireNotNull(adapter(http).resolvePost(PostId(SourceKey.HITOMI, "56")))

        assertEquals(2, post.creatorProfiles.size)
        assertEquals(maximumIdentity, post.creatorProfiles[0].profileId)
        assertEquals("artist:$maximumIdentity", post.creatorProfiles[0].uploadsQuery)
        assertEquals(replacementIdentity, post.creatorProfiles[1].profileId)
        assertEquals(
            "https://hitomi.la/artist/replacement%20%EF%BF%BD-all.html",
            post.creatorProfiles[1].profileUrl,
        )
    }

    @Test
    fun `creator browsing delegates to typed artist pagination and validates identity tokens`() = runTest {
        val artistUrl = HitomiNozomi.urlFor(
            HitomiNozomiRequest(area = "artist", tag = "arisue tsukasa"),
        )
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[artistUrl] = listOf(4, 3, 2)
        }
        val adapter = adapter(http, pageSize = 1)
        val creator = CreatorProfile(
            source = SourceKey.HITOMI,
            displayName = "Arisue Tsukasa",
            profileId = "arisue tsukasa",
            profileUrl = "https://hitomi.la/artist/arisue%20tsukasa-all.html",
            uploadsQuery = "artist:arisue tsukasa",
        )

        val first = adapter.searchCreatorPosts(creator, null)
        val second = adapter.searchCreatorPosts(creator, requireNotNull(first.nextPageToken))

        assertEquals(listOf("4"), first.items.map { post -> post.id.sourcePostId })
        assertEquals(listOf("3"), second.items.map { post -> post.id.sourcePostId })
        assertEquals(listOf(0L, 4L), http.binaryRequests.map { request -> request.range.startInclusive })
        assertTrue(http.binaryRequests.all { request -> request.url == artistUrl })

        val requestsBeforeInvalidProfiles = http.binaryRequests.size
        assertTrue(
            adapter.searchCreatorPosts(creator.copy(source = SourceKey.NHENTAI), null).items.isEmpty(),
        )
        assertTrue(
            adapter.searchCreatorPosts(creator.copy(profileId = ""), null).items.isEmpty(),
        )
        assertTrue(
            adapter.searchCreatorPosts(creator.copy(uploadsQuery = "arisue tsukasa"), null).items.isEmpty(),
        )
        assertTrue(
            adapter.searchCreatorPosts(creator.copy(uploadsQuery = "artist:someone else"), null).items.isEmpty(),
        )
        assertEquals(requestsBeforeInvalidProfiles, http.binaryRequests.size)

        assertParseFailure {
            adapter.searchCreatorPosts(
                creator.copy(
                    displayName = "Other",
                    profileId = "other",
                    uploadsQuery = "artist:other",
                ),
                requireNotNull(first.nextPageToken),
            )
        }
    }

    @Test
    fun `gallery hydration preserves cancellation`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(1)
            galleryDelayMs = 60_000L
        }
        val failure = runCatching {
            withTimeout(100L) {
                adapter(http).search(query(), null)
            }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `tokens facets and incomplete membership ranges fail closed`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val artist = HitomiNozomi.urlFor(HitomiNozomiRequest(area = "artist", tag = "najar"))
        val tag = HitomiNozomi.urlFor(HitomiNozomiRequest(area = "tag", tag = "safe"))
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(3, 2, 1)
            binaryIndexes[artist] = listOf(3, 2)
            binaryIndexes[tag] = listOf(3, 2, 1)
            truncatedRangeUrls += tag
        }
        val adapter = adapter(http, pageSize = 1)

        assertParseFailure { adapter.search(query(), "bnVsbA") }
        assertParseFailure {
            adapter.search(
                query(include = listOf(SearchTerm("najar", SearchFacet.ARTIST, "female"))),
                null,
            )
        }

        val first = adapter.search(query(), null)
        assertParseFailure {
            adapter.search(query(sort = SortMode.POPULAR), requireNotNull(first.nextPageToken))
        }
        assertParseFailure {
            adapter.search(
                query(
                    include = listOf(
                        SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                        SearchTerm("safe", SearchFacet.TAG, "tag"),
                    ),
                ),
                null,
            )
        }
    }

    @Test
    fun `protocol failures from public search and autocomplete are typed as parse errors`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val autocomplete = HitomiProtocol.autocompleteUrl("global", "bad")
        val http = RoutingHitomiHttpClient().apply {
            rawFullBodies[all] = byteArrayOf(0, 0, 1)
            textRoutes[autocomplete] = "not-json"
        }
        val adapter = adapter(http)

        assertParseFailure { adapter.search(query(), null) }
        assertParseFailure {
            adapter.autocompleteFaceted("bad", FacetedSearchScope.All, 10)
        }
    }

    @Test
    fun `shared media resolver failures surface while invalid galleries remain isolated`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(1)
        }
        val sharedFailureAdapter = adapter(
            http = http,
            candidateProvider = { throw HitomiProtocolException("bad gg.js") },
        )

        assertParseFailure { sharedFailureAdapter.search(query(), null) }
        assertParseFailure {
            sharedFailureAdapter.resolvePost(PostId(SourceKey.HITOMI, "1"))
        }

        val localHttp = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(1, 2, 3, 4)
            galleryBodies[2] = galleryWithFiles(
                id = 2,
                files = "[{\"name\":\"1.jpg\",\"hash\":\"invalid\",\"width\":1,\"height\":1}]",
            )
            galleryBodies[3] = galleryWithFiles(id = 3, files = "[]")
            galleryBodies[4] = galleryWithFiles(id = 4, files = "[]", type = "anime")
        }
        val localAdapter = adapter(localHttp, pageSize = 4)
        val cards = localAdapter.search(query(), null).items

        assertEquals(listOf("1"), cards.map { post -> post.id.sourcePostId })
        assertNotNull(cards.single().preview.url)
        assertParseFailure { localAdapter.resolvePost(PostId(SourceKey.HITOMI, "2")) }
        assertParseFailure { localAdapter.resolvePost(PostId(SourceKey.HITOMI, "3")) }
        assertParseFailure { localAdapter.resolvePost(PostId(SourceKey.HITOMI, "4")) }

        val candidateLessAdapter = adapter(
            http = http,
            candidateProvider = { emptyList() },
        )
        assertParseFailure { candidateLessAdapter.search(query(), null) }
    }

    @Test
    fun `oversized range ignoring Nozomi response becomes a typed source error`() = runTest {
        val all = HitomiNozomi.urlFor(HitomiNozomiRequest())
        val http = RoutingHitomiHttpClient().apply {
            binaryFailures[all] = SourceHttpBodyTooLargeException(64)
        }

        assertParseFailure { adapter(http).search(query(sort = SortMode.RANDOM), null) }

        val partial = RoutingHitomiHttpClient().apply {
            binaryIndexes[all] = listOf(3, 2, 1)
            truncatedRangeUrls += all
        }
        assertParseFailure { adapter(partial).search(query(sort = SortMode.RANDOM), null) }
    }

    private fun adapter(
        http: RoutingHitomiHttpClient,
        pageSize: Int = 25,
        hydrationConcurrency: Int = 4,
        globalIndexCacheMaxBytes: Long = HitomiGlobalIndexCache.DEFAULT_MAX_BYTES,
        mediaCalls: AtomicInteger = AtomicInteger(0),
        candidateProvider: (suspend (HitomiMediaFile) -> List<HitomiMediaCandidate>)? = null,
    ): HitomiSourceAdapter {
        return HitomiSourceAdapter(
            httpClient = http,
            pageSize = pageSize,
            hydrationConcurrency = hydrationConcurrency,
            globalIndexCacheMaxBytes = globalIndexCacheMaxBytes,
            mediaCandidates = candidateProvider ?: { file ->
                mediaCalls.incrementAndGet()
                listOf(
                    candidate(file, HitomiMediaFormat.AVIF, "image/avif", "avif"),
                    candidate(file, HitomiMediaFormat.WEBP, "image/webp", "webp"),
                    candidate(file, HitomiMediaFormat.ORIGINAL, "image/jpeg", "original"),
                )
            },
        )
    }

    private fun candidate(
        file: HitomiMediaFile,
        format: HitomiMediaFormat,
        mime: String,
        path: String,
    ) = HitomiMediaCandidate(
        url = "https://media.test/$path/${file.hash}",
        mime = mime,
        format = format,
        shard = 1,
        isAlternateShard = false,
        configurationBasePath = "fixture/",
        configurationVersion = "fixture",
    )

    private fun query(
        include: List<SearchTerm> = emptyList(),
        exclude: List<SearchTerm> = emptyList(),
        sort: SortMode = SortMode.NEWEST,
    ) = Query(
        mode = QueryMode.Source(SourceKey.HITOMI),
        includeTerms = include,
        excludeTerms = exclude,
        sort = sort,
        dateRange = null,
        minScore = null,
    )

    private fun randomLanguageQuery(language: String): Query {
        return query(
            include = listOf(SearchTerm(language, SearchFacet.LANGUAGE, "language")),
            sort = SortMode.RANDOM,
        )
    }

    private suspend fun assertParseFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected parse failure, got $failure", failure is SourceAdapterException)
        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/hitomi/2026-07-10/$name"),
    ).readText()

    private fun decodeRandomSeed(token: String): Long {
        val json = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
        return JsonParser.parseString(json).asJsonObject.get("randomSeed").asLong
    }

    private fun decodeTokenString(token: String, field: String): String? {
        val json = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
        return JsonParser.parseString(json).asJsonObject.get(field)
            ?.takeUnless { value -> value.isJsonNull }
            ?.asString
    }

    private fun String.asLegacyVersionTwoToken(): String {
        val json = Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)
        val token = JsonParser.parseString(json).asJsonObject.apply {
            addProperty("version", 2)
            remove("globalIndexVersion")
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toString().toByteArray(Charsets.UTF_8))
    }

    private fun RoutingHitomiHttpClient.routeGlobalIndex(
        version: String,
        term: String,
        ids: List<Int>,
    ) {
        val record = globalGalleryRecord(ids)
        rawFullBodies["https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"] =
            globalIndexNode(term, dataLength = record.size)
        rawFullBodies["https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"] = record
    }

    private fun globalIndexNode(term: String, dataLength: Int): ByteArray {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(term.toByteArray(Charsets.UTF_8))
            .copyOf(4)
        return ByteBuffer.allocate(464).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1)
            putInt(key.size)
            put(key)
            putInt(1)
            putLong(0L)
            putInt(dataLength)
            repeat(17) { putLong(0L) }
        }.array()
    }

    private fun globalGalleryRecord(ids: List<Int>): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES + ids.size * Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(ids.size)
                ids.forEach(::putInt)
            }.array()
    }

    private fun galleryWithFiles(
        id: Int,
        files: String,
        type: String = "doujinshi",
    ): String {
        return """var galleryinfo = {"id":"$id","title":"Gallery $id","galleryurl":"/galleries/$id.html","type":"$type","language":"english","blocked":0,"tags":[],"artists":[],"files":$files};"""
    }

    private fun galleryWithArtists(id: Int, artists: String): String {
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        return """var galleryinfo = {"id":"$id","title":"Gallery $id","galleryurl":"/galleries/$id.html","type":"doujinshi","language":"english","blocked":0,"tags":[],"artists":$artists,"files":[{"name":"1.jpg","hash":"$hash","width":800,"height":1200,"hasavif":1}]};"""
    }
}

private class RoutingHitomiHttpClient : SourceHttpClient {
    data class TextRequest(val url: String, val headers: Map<String, String>)
    data class BinaryRequest(val url: String, val range: SourceByteRange, val maxBodyBytes: Int)

    val textRoutes = ConcurrentHashMap<String, String>()
    val galleryBodies = ConcurrentHashMap<Int, String>()
    val galleryStatuses = ConcurrentHashMap<Int, Int>()
    val binaryIndexes = ConcurrentHashMap<String, List<Int>>()
    val rawFullBodies = ConcurrentHashMap<String, ByteArray>()
    val binaryFailures = ConcurrentHashMap<String, RuntimeException>()
    val fullResponseUrls = ConcurrentHashMap.newKeySet<String>()
    val truncatedRangeUrls = ConcurrentHashMap.newKeySet<String>()
    val textRequests = mutableListOf<TextRequest>()
    val binaryRequests = mutableListOf<BinaryRequest>()
    val maxGalleryConcurrency = AtomicInteger(0)
    var galleryDelayMs: Long = 0L

    private val activeGalleryRequests = AtomicInteger(0)

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        synchronized(textRequests) { textRequests += TextRequest(url, headers) }
        val galleryId = GALLERY_URL.matchEntire(url)?.groupValues?.get(1)?.toIntOrNull()
        if (galleryId != null) {
            val active = activeGalleryRequests.incrementAndGet()
            maxGalleryConcurrency.getAndUpdate { previous -> maxOf(previous, active) }
            return try {
                if (galleryDelayMs > 0L) delay(galleryDelayMs)
                val status = galleryStatuses[galleryId] ?: 200
                SourceHttpResponse(
                    statusCode = status,
                    body = galleryBodies[galleryId] ?: generatedGallery(galleryId),
                )
            } finally {
                activeGalleryRequests.decrementAndGet()
            }
        }
        return textRoutes[url]
            ?.let { body -> SourceHttpResponse(200, body) }
            ?: SourceHttpResponse(404, "")
    }

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse {
        val requested = requireNotNull(range)
        synchronized(binaryRequests) {
            binaryRequests += BinaryRequest(url, requested, maxBodyBytes)
        }
        binaryFailures[url]?.let { failure -> throw failure }
        rawFullBodies[url]?.let { body -> return SourceByteResponse(200, body) }
        val ids = binaryIndexes[url] ?: return SourceByteResponse(404, ByteArray(0))
        if (url in fullResponseUrls) {
            return SourceByteResponse(200, encodeIds(ids))
        }
        val first = (requested.startInclusive / Int.SIZE_BYTES).toInt()
        if (first >= ids.size) return SourceByteResponse(416, ByteArray(0))
        val requestedExclusive = (requested.endInclusive / Int.SIZE_BYTES + 1L)
            .coerceAtMost(ids.size.toLong())
            .toInt()
        val selected = ids.subList(first, requestedExclusive)
        val returned = if (url in truncatedRangeUrls && selected.size > 1) selected.take(1) else selected
        val body = encodeIds(returned)
        val startByte = first.toLong() * Int.SIZE_BYTES
        val endByte = startByte + body.size - 1L
        val totalBytes = ids.size.toLong() * Int.SIZE_BYTES
        return SourceByteResponse(
            statusCode = 206,
            body = body,
            headers = mapOf("Content-Range" to listOf("bytes $startByte-$endByte/$totalBytes")),
        )
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = SourceHttpResponse(405, "")

    companion object {
        private val GALLERY_URL = Regex("https://ltn\\.gold-usergeneratedcontent\\.net/galleries/(\\d+)\\.js")
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        private fun generatedGallery(id: Int): String {
            return """var galleryinfo = {"id":"$id","title":"Gallery $id","galleryurl":"/galleries/$id.html","type":"doujinshi","language":"english","date":"2026-07-09 23:41:00-05","blocked":0,"tags":[{"tag":"safe"}],"artists":[{"artist":"artist $id"}],"files":[{"name":"1.jpg","hash":"$HASH","width":800,"height":1200,"hasavif":1}]};"""
        }

        private fun encodeIds(ids: List<Int>): ByteArray {
            val buffer = ByteBuffer.allocate(ids.size * Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
            ids.forEach(buffer::putInt)
            return buffer.array()
        }
    }
}
