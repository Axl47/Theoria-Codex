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
internal abstract class HitomiSourceAdapterTestFixture {
    protected fun adapter(
        http: RoutingHitomiHttpClient,
        pageSize: Int = 25,
        hydrationConcurrency: Int = 4,
        globalIndexCacheMaxBytes: Long = HitomiGlobalIndexCache.DEFAULT_MAX_BYTES,
        randomSnapshotCacheMaxBytes: Long = HitomiRandomSnapshotCache.DEFAULT_MAX_BYTES,
        mediaCalls: AtomicInteger = AtomicInteger(0),
        candidateProvider: (suspend (HitomiMediaFile) -> List<HitomiMediaCandidate>)? = null,
    ): HitomiSourceAdapter {
        return HitomiSourceAdapter(
            httpClient = http,
            pageSize = pageSize,
            hydrationConcurrency = hydrationConcurrency,
            globalIndexCacheMaxBytes = globalIndexCacheMaxBytes,
            randomSnapshotCacheMaxBytes = randomSnapshotCacheMaxBytes,
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

    protected fun candidate(
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

    protected fun query(
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

    protected fun randomLanguageQuery(language: String): Query {
        return query(
            include = listOf(SearchTerm(language, SearchFacet.LANGUAGE, "language")),
            sort = SortMode.RANDOM,
        )
    }

    protected suspend fun assertParseFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected parse failure, got $failure", failure is SourceAdapterException)
        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
    }

    protected fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/hitomi/2026-07-10/$name"),
    ).readText()

    protected fun decodeRandomSeed(token: String): Long {
        val json = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
        return JsonParser.parseString(json).asJsonObject.get("randomSeed").asLong
    }

    protected fun decodeTokenString(token: String, field: String): String? {
        val json = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
        return JsonParser.parseString(json).asJsonObject.get(field)
            ?.takeUnless { value -> value.isJsonNull }
            ?.asString
    }

    protected fun String.asLegacyVersionTwoToken(): String {
        val json = Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)
        val token = JsonParser.parseString(json).asJsonObject.apply {
            addProperty("version", 2)
            remove("globalIndexVersion")
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toString().toByteArray(Charsets.UTF_8))
    }

    protected fun String.asPreviousVersionThreeToken(): String {
        val json = Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)
        val token = JsonParser.parseString(json).asJsonObject.apply {
            addProperty("version", 3)
            remove("randomPermutationVersion")
        }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toString().toByteArray(Charsets.UTF_8))
    }

    protected fun RoutingHitomiHttpClient.routeGlobalIndex(
        version: String,
        term: String,
        ids: List<Int>,
    ) {
        val record = globalGalleryRecord(ids)
        rawFullBodies["https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.index"] =
            globalIndexNode(term, dataLength = record.size)
        rawFullBodies["https://ltn.gold-usergeneratedcontent.net/galleriesindex/galleries.$version.data"] = record
    }

    protected fun globalIndexNode(term: String, dataLength: Int): ByteArray {
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

    protected fun globalGalleryRecord(ids: List<Int>): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES + ids.size * Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(ids.size)
                ids.forEach(::putInt)
            }.array()
    }

    protected fun galleryWithFiles(
        id: Int,
        files: String,
        type: String = "doujinshi",
    ): String {
        return """var galleryinfo = {"id":"$id","title":"Gallery $id","galleryurl":"/galleries/$id.html","type":"$type","language":"english","blocked":0,"tags":[],"artists":[],"files":$files};"""
    }

    protected fun galleryWithArtists(id: Int, artists: String): String {
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        return """var galleryinfo = {"id":"$id","title":"Gallery $id","galleryurl":"/galleries/$id.html","type":"doujinshi","language":"english","blocked":0,"tags":[],"artists":$artists,"files":[{"name":"1.jpg","hash":"$hash","width":800,"height":1200,"hasavif":1}]};"""
    }
}

internal class RoutingHitomiHttpClient : SourceHttpClient {
    data class TextRequest(val url: String, val headers: Map<String, String>)
    data class BinaryRequest(val url: String, val range: SourceByteRange, val maxBodyBytes: Int)

    val textRoutes = ConcurrentHashMap<String, String>()
    val galleryBodies = ConcurrentHashMap<Int, String>()
    val galleryStatuses = ConcurrentHashMap<Int, Int>()
    val binaryIndexes = ConcurrentHashMap<String, List<Int>>()
    val rawFullBodies = ConcurrentHashMap<String, ByteArray>()
    val binaryFailures = ConcurrentHashMap<String, RuntimeException>()
    val binaryRequestStarted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val binaryRequestRelease = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val fullResponseUrls = ConcurrentHashMap.newKeySet<String>()
    val truncatedRangeUrls = ConcurrentHashMap.newKeySet<String>()
    val textRequests = mutableListOf<TextRequest>()
    val binaryRequests = mutableListOf<BinaryRequest>()
    val maxGalleryConcurrency = AtomicInteger(0)
    var galleryDelayMs: Long = 0L

    private val activeGalleryRequests = AtomicInteger(0)

    fun galleryRequestCount(galleryId: Int): Int = synchronized(textRequests) {
        textRequests.count { request -> request.url == HitomiProtocol.galleryUrl(galleryId) }
    }

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
        binaryRequestStarted[url]?.complete(Unit)
        binaryRequestRelease[url]?.await()
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
