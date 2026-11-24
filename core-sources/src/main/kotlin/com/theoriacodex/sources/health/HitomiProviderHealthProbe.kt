package com.theoriacodex.sources.health

import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.hitomi.HitomiMediaUrlResolver
import com.theoriacodex.sources.hitomi.HitomiProtocol
import com.theoriacodex.sources.hitomi.HitomiProtocolException
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.header
import java.io.IOException

class HitomiProviderHealthProbe(
    private val adapter: SourceAdapter,
    private val httpClient: SourceHttpClient,
    private val mediaUrlResolver: HitomiMediaUrlResolver = HitomiMediaUrlResolver(httpClient),
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val probeMediaRanges: Boolean = true,
    private val challengesSeen: () -> Int = { 0 },
) {
    private val steps = ProbeSteps(
        source = SourceKey.HITOMI,
        nowProvider = nowProvider,
        challengesSeen = challengesSeen,
        failureReason = { it.toFailureReason() },
    )

    init {
        require(adapter.sourceKey == SourceKey.HITOMI) {
            "Hitomi provider health requires the Hitomi source adapter"
        }
    }

    suspend fun runAll(): List<ProviderProbeStepResult> {
        return listOfNotNull(
            steps.run(
                checkName = GG_CONFIGURATION_STEP,
                requestUrl = { HitomiMediaUrlResolver.GG_CONFIGURATION_URL },
            ) {
                val configuration = mediaUrlResolver.refresh()
                steps.result(
                    status = ProviderHealthStatus.OK,
                    checkName = GG_CONFIGURATION_STEP,
                    requestUrl = HitomiMediaUrlResolver.GG_CONFIGURATION_URL,
                    message = "Parsed gg.js configuration ${configuration.version}",
                )
            },
            probeGalleryResolve(
                galleryId = ANIMATED_GALLERY_ID,
                checkName = ANIMATED_GALLERY_RESOLVE_STEP,
            ) { post ->
                val animatedMedia = post.media.count(ImageRef::isAnimated)
                when {
                    post.media.size != ANIMATED_GALLERY_MEDIA_COUNT -> {
                        "Expected $ANIMATED_GALLERY_MEDIA_COUNT ordered media items, returned ${post.media.size}"
                    }
                    animatedMedia != post.media.size -> {
                        "Expected every gallery item to be marked animated, returned $animatedMedia animated items"
                    }
                    post.creatorProfiles.none { creator ->
                        creator.canonicalHitomiArtistIdentity() == "najar"
                    } -> "Expected gallery $ANIMATED_GALLERY_ID to expose Najar as a valid creator profile"
                    else -> null
                }
            },
            if (probeMediaRanges) probeAnimatedWebpRange() else null,
            probeGalleryResolve(
                galleryId = ANIME_GALLERY_ID,
                checkName = ANIME_GALLERY_RESOLVE_STEP,
            ) { post ->
                val video = post.media.singleOrNull()
                when {
                    video == null -> "Expected one playable anime media item, returned ${post.media.size}"
                    !video.mime.equals("video/mp4", ignoreCase = true) -> {
                        "Expected video/mp4 anime media, returned ${video.mime ?: "no MIME"}"
                    }
                    post.creatorProfiles.isNotEmpty() || post.creatorProfile != null -> {
                        "Expected gallery $ANIME_GALLERY_ID to have no creator action"
                    }
                    else -> null
                }
            },
            if (probeMediaRanges) probeAnimeMp4Range() else null,
        )
    }

    private suspend fun probeGalleryResolve(
        galleryId: Int,
        checkName: String,
        contractProblem: (Post) -> String?,
    ): ProviderProbeStepResult {
        val galleryUrl = HitomiProtocol.galleryUrl(galleryId)
        return steps.run(checkName = checkName, requestUrl = { galleryUrl }) {
            val post = adapter.resolvePost(PostId(SourceKey.HITOMI, galleryId.toString()))
                ?: return@run steps.result(
                    status = ProviderHealthStatus.DEGRADED,
                    checkName = checkName,
                    requestUrl = galleryUrl,
                    message = "Resolve returned null for gallery $galleryId",
                )
            val problem = contractProblem(post)
            if (problem == null) {
                steps.result(
                    status = ProviderHealthStatus.OK,
                    checkName = checkName,
                    requestUrl = galleryUrl,
                    itemCount = post.media.size,
                    samplePostId = post.id.sourcePostId,
                    message = "Resolved gallery $galleryId with ${post.media.size} media items",
                )
            } else {
                steps.result(
                    status = ProviderHealthStatus.DEGRADED,
                    checkName = checkName,
                    requestUrl = galleryUrl,
                    itemCount = post.media.size,
                    samplePostId = post.id.sourcePostId,
                    message = problem,
                )
            }
        }
    }

    private suspend fun probeAnimatedWebpRange(): ProviderProbeStepResult {
        var requestUrl = HitomiProtocol.galleryUrl(ANIMATED_GALLERY_ID)
        return steps.run(
            checkName = ANIMATED_WEBP_RANGE_STEP,
            requestUrl = { requestUrl },
        ) {
            val post = requireResolvedGallery(ANIMATED_GALLERY_ID)
            val media = post.media.firstOrNull { ref ->
                ref.isAnimated && ref.webpLocationOrNull() != null
            } ?: throw HitomiProtocolException(
                "Gallery $ANIMATED_GALLERY_ID did not expose an animated WebP candidate",
            )
            requestUrl = requireNotNull(media.webpLocationOrNull())
            val response = httpClient.getBytes(
                url = requestUrl,
                headers = HitomiProtocol.requestHeaders,
                range = SourceByteRange(0L, WEBP_RANGE_BYTES - 1L),
                maxBodyBytes = WEBP_RANGE_BYTES.toInt(),
            )
            validateMediaStatus(response, requestUrl, "animated WebP")
            val contentType = response.headers.header("Content-Type")?.substringBefore(';')?.trim()
            val problem = when {
                !contentType.equals("image/webp", ignoreCase = true) -> {
                    "Expected image/webp, returned ${contentType ?: "no Content-Type"}"
                }
                !response.body.hasWebpSignature() -> "Range response did not contain a WebP signature"
                !response.body.hasAsciiChunk("ANIM") || !response.body.hasAsciiChunk("ANMF") -> {
                    "Range response did not expose animated WebP chunks"
                }
                response.statusCode == 206 && !response.hasInitialContentRange() -> {
                    "Partial WebP response omitted a valid initial Content-Range"
                }
                else -> null
            }
            if (problem == null) {
                steps.result(
                    status = ProviderHealthStatus.OK,
                    checkName = ANIMATED_WEBP_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIMATED_GALLERY_ID.toString(),
                    message = "Verified bounded animated WebP range (${response.body.size} bytes)",
                )
            } else {
                steps.result(
                    status = ProviderHealthStatus.DEGRADED,
                    checkName = ANIMATED_WEBP_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIMATED_GALLERY_ID.toString(),
                    message = problem,
                )
            }
        }
    }

    private suspend fun probeAnimeMp4Range(): ProviderProbeStepResult {
        var requestUrl = HitomiProtocol.galleryUrl(ANIME_GALLERY_ID)
        return steps.run(
            checkName = ANIME_MP4_RANGE_STEP,
            requestUrl = { requestUrl },
        ) {
            val post = requireResolvedGallery(ANIME_GALLERY_ID)
            val media = post.media.singleOrNull()
                ?.takeIf { ref -> ref.mime.equals("video/mp4", ignoreCase = true) }
                ?: throw HitomiProtocolException(
                    "Gallery $ANIME_GALLERY_ID did not expose exactly one MP4 media item",
                )
            requestUrl = media.url?.takeIf(String::isNotBlank)
                ?: throw HitomiProtocolException("Gallery $ANIME_GALLERY_ID exposed a blank MP4 URL")
            val response = httpClient.getBytes(
                url = requestUrl,
                headers = HitomiProtocol.requestHeaders,
                range = SourceByteRange(0L, MP4_RANGE_BYTES - 1L),
                maxBodyBytes = MP4_RANGE_BYTES.toInt(),
            )
            validateMediaStatus(response, requestUrl, "MP4")
            val contentType = response.headers.header("Content-Type")?.substringBefore(';')?.trim()
            val problem = when {
                !contentType.equals("video/mp4", ignoreCase = true) -> {
                    "Expected video/mp4, returned ${contentType ?: "no Content-Type"}"
                }
                !response.body.hasMp4Signature() -> "Range response did not contain an MP4 ftyp signature"
                response.statusCode == 206 && !response.hasInitialContentRange() -> {
                    "Partial MP4 response omitted a valid initial Content-Range"
                }
                else -> null
            }
            if (problem == null) {
                steps.result(
                    status = ProviderHealthStatus.OK,
                    checkName = ANIME_MP4_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIME_GALLERY_ID.toString(),
                    message = "Verified bounded MP4 range (${response.body.size} bytes)",
                )
            } else {
                steps.result(
                    status = ProviderHealthStatus.DEGRADED,
                    checkName = ANIME_MP4_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIME_GALLERY_ID.toString(),
                    message = problem,
                )
            }
        }
    }

    private suspend fun requireResolvedGallery(galleryId: Int): Post {
        return adapter.resolvePost(PostId(SourceKey.HITOMI, galleryId.toString()))
            ?: throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi gallery $galleryId resolve returned null (${HitomiProtocol.galleryUrl(galleryId)})",
            )
    }

    private fun validateMediaStatus(
        response: SourceByteResponse,
        requestUrl: String,
        mediaLabel: String,
    ) {
        if (response.statusCode != 200 && response.statusCode != 206) {
            throw SourceAdapterException(
                reason = classifyHttpFailure(response.statusCode),
                message = "Hitomi $mediaLabel range failed ($requestUrl, HTTP ${response.statusCode})",
            )
        }
    }

    private fun ImageRef.webpLocationOrNull(): String? {
        return buildList {
            url?.let(::add)
            addAll(progressiveUrls)
            addAll(fallbackUrls)
        }.firstOrNull { location ->
            location.substringBefore('?').endsWith(".webp", ignoreCase = true)
        }
    }

    private fun SourceByteResponse.hasInitialContentRange(): Boolean {
        val value = headers.header("Content-Range") ?: return false
        return INITIAL_CONTENT_RANGE.matches(value.trim())
    }

    private fun ByteArray.hasWebpSignature(): Boolean {
        return size >= 12 &&
            copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    }

    private fun ByteArray.hasMp4Signature(): Boolean {
        return size >= 12 && copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
    }

    private fun ByteArray.hasAsciiChunk(chunk: String): Boolean {
        val expected = chunk.toByteArray(Charsets.US_ASCII)
        if (expected.isEmpty() || size < expected.size) return false
        return indices.any { start ->
            start <= size - expected.size && expected.indices.all { offset ->
                this[start + offset] == expected[offset]
            }
        }
    }

    private fun Throwable.toFailureReason(): SourceFailureReason? {
        return when (this) {
            is SourceAdapterException -> reason
            is HitomiProtocolException -> SourceFailureReason.PARSE
            is IOException -> SourceFailureReason.NETWORK
            else -> null
        }
    }

    companion object {
        const val GG_CONFIGURATION_STEP = "gg-configuration"
        const val ANIMATED_GALLERY_RESOLVE_STEP = "gallery-4042375-resolve"
        const val ANIMATED_WEBP_RANGE_STEP = "gallery-4042375-animated-webp-range"
        const val ANIME_GALLERY_RESOLVE_STEP = "gallery-7231-resolve"
        const val ANIME_MP4_RANGE_STEP = "gallery-7231-mp4-range"

        const val ANIMATED_GALLERY_ID = 4_042_375
        const val ANIME_GALLERY_ID = 7_231
        const val ANIMATED_GALLERY_MEDIA_COUNT = 44

        private const val WEBP_RANGE_BYTES = 64L * 1024L
        private const val MP4_RANGE_BYTES = 1_024L
        private val INITIAL_CONTENT_RANGE = Regex("bytes\\s+0-\\d+/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}
