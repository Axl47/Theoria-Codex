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
internal class HitomiCreatorFailureSourceAdapterTest : HitomiSourceAdapterTestFixture() {
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

}
