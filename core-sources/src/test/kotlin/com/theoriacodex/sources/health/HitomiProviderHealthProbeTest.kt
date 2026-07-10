package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.hitomi.HitomiMediaUrlResolver
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiProviderHealthProbeTest {
    @Test
    fun `exact Hitomi probes verify configuration galleries and bounded media signatures`() = runTest {
        val http = ProbeHttpClient()
        val probe = HitomiProviderHealthProbe(
            adapter = ExactGalleryAdapter(),
            httpClient = http,
            nowProvider = { 123L },
        )

        val results = probe.runAll()

        assertEquals(
            listOf(
                HitomiProviderHealthProbe.GG_CONFIGURATION_STEP,
                HitomiProviderHealthProbe.ANIMATED_GALLERY_RESOLVE_STEP,
                HitomiProviderHealthProbe.ANIMATED_WEBP_RANGE_STEP,
                HitomiProviderHealthProbe.ANIME_GALLERY_RESOLVE_STEP,
                HitomiProviderHealthProbe.ANIME_MP4_RANGE_STEP,
            ),
            results.map(ProviderProbeStepResult::checkName),
        )
        assertTrue(results.all { result -> result.status == ProviderHealthStatus.OK })
        assertTrue(results.all { result -> !result.requestUrl.isNullOrBlank() })
        assertTrue(results.all { result -> result.checkedAtEpochMs == 123L })
        assertEquals(
            listOf(
                SourceByteRange(0L, 65_535L),
                SourceByteRange(0L, 1_023L),
            ),
            http.byteRequests.map(ByteRequest::range),
        )
        assertEquals(listOf(65_536, 1_024), http.byteRequests.map(ByteRequest::maxBodyBytes))
        assertTrue(http.byteRequests.all { request -> request.headers["Referer"] == "https://hitomi.la/" })
    }

    @Test
    fun `media signature drift is reported against the exact request URL`() = runTest {
        val webpUrl = ExactGalleryAdapter.ANIMATED_WEBP_URL
        val http = ProbeHttpClient(webpBody = "not-webp".toByteArray())

        val result = HitomiProviderHealthProbe(
            adapter = ExactGalleryAdapter(),
            httpClient = http,
        ).runAll().single { step ->
            step.checkName == HitomiProviderHealthProbe.ANIMATED_WEBP_RANGE_STEP
        }

        assertEquals(ProviderHealthStatus.DEGRADED, result.status)
        assertEquals(webpUrl, result.requestUrl)
        assertTrue(result.message.orEmpty().contains("WebP signature"))
    }

    @Test
    fun `Hitomi exact probes rethrow cancellation instead of recording a failure`() = runTest {
        val http = ProbeHttpClient(ggFailure = CancellationException("cancel gg"))

        val failure = runCatching {
            HitomiProviderHealthProbe(
                adapter = ExactGalleryAdapter(),
                httpClient = http,
            ).runAll()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private class ProbeHttpClient(
        private val webpBody: ByteArray = animatedWebpBytes(),
        private val ggFailure: Throwable? = null,
    ) : SourceHttpClient {
        val byteRequests = mutableListOf<ByteRequest>()

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            ggFailure?.let { throw it }
            assertEquals(HitomiMediaUrlResolver.GG_CONFIGURATION_URL, url)
            return SourceHttpResponse(statusCode = 200, body = currentGgFixture())
        }

        override suspend fun getBytes(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            range: SourceByteRange?,
            maxBodyBytes: Int,
        ): SourceByteResponse {
            byteRequests += ByteRequest(
                url = url,
                headers = headers,
                range = requireNotNull(range),
                maxBodyBytes = maxBodyBytes,
            )
            return if (url == ExactGalleryAdapter.ANIMATED_WEBP_URL) {
                SourceByteResponse(
                    statusCode = 206,
                    body = webpBody,
                    headers = mapOf(
                        "content-type" to listOf("image/webp"),
                        "content-range" to listOf("bytes 0-${webpBody.lastIndex}/${webpBody.size}"),
                    ),
                )
            } else {
                val body = mp4Bytes()
                SourceByteResponse(
                    statusCode = 206,
                    body = body,
                    headers = mapOf(
                        "Content-Type" to listOf("video/mp4"),
                        "Content-Range" to listOf("bytes 0-${body.lastIndex}/196139272"),
                    ),
                )
            }
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("Unexpected POST $url")

        private fun currentGgFixture(): String = requireNotNull(
            javaClass.getResource("/hitomi/2026-07-10/gg-current-inverted-shape.js"),
        ).readText()
    }

    private class ExactGalleryAdapter : SourceAdapter {
        override val sourceKey: SourceKey = SourceKey.HITOMI
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true,
            supportsSortPopular = true,
            supportsSortTop = true,
            supportsSortRandom = true,
            supportsExcludeTagsServerSide = true,
            supportsDateRangeServerSide = false,
            supportsMinScoreServerSide = false,
            requiresCredentials = false,
        )

        override suspend fun resolvePost(id: PostId): Post? {
            return when (id.sourcePostId) {
                HitomiProviderHealthProbe.ANIMATED_GALLERY_ID.toString() -> animatedGallery()
                HitomiProviderHealthProbe.ANIME_GALLERY_ID.toString() -> animeGallery()
                else -> null
            }
        }

        override suspend fun search(query: Query, pageToken: String?): Page<Post> = error("Unexpected search")
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = error("Unexpected trending")
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = error("Unexpected autocomplete")
        override suspend fun quickQuery(kind: QuickQueryKind): Query {
            return Query(
                mode = QueryMode.Source(SourceKey.HITOMI),
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            )
        }

        private fun animatedGallery(): Post {
            val media = List(HitomiProviderHealthProbe.ANIMATED_GALLERY_MEDIA_COUNT) {
                ImageRef(
                    url = ANIMATED_WEBP_URL,
                    localPath = null,
                    mime = "image/webp",
                    isAnimated = true,
                )
            }
            val creator = CreatorProfile(
                source = SourceKey.HITOMI,
                displayName = "Najar",
                profileId = "najar",
                profileUrl = "https://hitomi.la/artist/najar-all.html",
                uploadsQuery = "artist:najar",
            )
            return post(
                id = HitomiProviderHealthProbe.ANIMATED_GALLERY_ID,
                media = media,
                creatorProfile = creator,
                creatorProfiles = listOf(creator),
            )
        }

        private fun animeGallery(): Post {
            return post(
                id = HitomiProviderHealthProbe.ANIME_GALLERY_ID,
                media = listOf(
                    ImageRef(
                        url = ANIME_MP4_URL,
                        localPath = null,
                        mime = "video/mp4",
                    ),
                ),
            )
        }

        private fun post(
            id: Int,
            media: List<ImageRef>,
            creatorProfile: CreatorProfile? = null,
            creatorProfiles: List<CreatorProfile> = emptyList(),
        ): Post {
            return Post(
                id = PostId(SourceKey.HITOMI, id.toString()),
                preview = media.first(),
                full = media.first(),
                media = media,
                pageUrl = "https://hitomi.la/reader/$id.html",
                width = null,
                height = null,
                canonicalTags = emptyList(),
                rawTags = emptyList(),
                authorName = creatorProfile?.displayName,
                createdAtEpochMs = null,
                creatorProfile = creatorProfile,
                creatorProfiles = creatorProfiles,
                mediaCount = media.size,
            )
        }

        companion object {
            const val ANIMATED_WEBP_URL = "https://w1.gold-usergeneratedcontent.net/version/1/animated.webp"
            const val ANIME_MP4_URL = "https://streaming.gold-usergeneratedcontent.net/videos/anime.mp4"
        }
    }

    private data class ByteRequest(
        val url: String,
        val headers: Map<String, String>,
        val range: SourceByteRange,
        val maxBodyBytes: Int,
    )

    companion object {
        private fun animatedWebpBytes(): ByteArray {
            return "RIFF0000WEBPVP8XANIMANMF".toByteArray(Charsets.US_ASCII)
        }

        private fun mp4Bytes(): ByteArray {
            return byteArrayOf(
                0, 0, 0, 16,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0, 0, 0, 0,
            )
        }
    }
}
