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
internal class HitomiFacetHydrationSourceAdapterTest : HitomiSourceAdapterTestFixture() {
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
        assertSparseAnimeCard(cards.last())
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
        assertResolvedAnime(anime)
        assertEquals(47, mediaCalls.get())
        assertEquals(1, http.galleryRequestCount(4_042_375))
        assertEquals(1, http.galleryRequestCount(7_231))
    }

    private fun assertSparseAnimeCard(post: Post) {
        assertEquals(1, post.mediaCount)
        assertEquals(1, post.media.size)
        assertEquals("video/mp4", post.full?.mime)
        assertTrue(post.full?.url?.contains("/videos/") == true)
        assertNull(post.creatorProfile)
        assertTrue(post.creatorProfiles.isEmpty())
    }

    private fun assertResolvedAnime(post: Post) {
        assertEquals(1, post.media.size)
        assertEquals("video/mp4", post.media.single().mime)
        assertTrue(post.media.single().url?.contains("/videos/") == true)
        assertEquals(1, post.mediaCount)
        assertNull(post.creatorProfile)
        assertTrue(post.creatorProfiles.isEmpty())
        assertNull(post.authorName)
    }
}
