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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.system.measureTimeMillis

/**
 * Exercises the provider-owned routes that a generic search probe cannot prove: mutable CDN
 * configuration, exact regression galleries, and bounded media range transport.
 */
class HitomiProviderHealthProbe(
    private val adapter: SourceAdapter,
    private val httpClient: SourceHttpClient,
    private val mediaUrlResolver: HitomiMediaUrlResolver = HitomiMediaUrlResolver(httpClient),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    init {
        require(adapter.sourceKey == SourceKey.HITOMI) {
            "Hitomi provider health requires the Hitomi source adapter"
        }
    }

    suspend fun runAll(): List<ProviderProbeStepResult> {
        return listOf(
            runStep(
                checkName = GG_CONFIGURATION_STEP,
                initialRequestUrl = HitomiMediaUrlResolver.GG_CONFIGURATION_URL,
            ) {
                val configuration = mediaUrlResolver.refresh()
                ok(
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
            probeAnimatedWebpRange(),
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
            probeAnimeMp4Range(),
        )
    }

    private suspend fun probeGalleryResolve(
        galleryId: Int,
        checkName: String,
        contractProblem: (Post) -> String?,
    ): ProviderProbeStepResult {
        val galleryUrl = HitomiProtocol.galleryUrl(galleryId)
        return runStep(checkName = checkName, initialRequestUrl = galleryUrl) {
            val post = adapter.resolvePost(PostId(SourceKey.HITOMI, galleryId.toString()))
                ?: return@runStep degraded(
                    checkName = checkName,
                    requestUrl = galleryUrl,
                    message = "Resolve returned null for gallery $galleryId",
                )
            val problem = contractProblem(post)
            if (problem == null) {
                ok(
                    checkName = checkName,
                    requestUrl = galleryUrl,
                    itemCount = post.media.size,
                    samplePostId = post.id.sourcePostId,
                    message = "Resolved gallery $galleryId with ${post.media.size} media items",
                )
            } else {
                degraded(
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
        return runStep(
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
            val contentType = response.headerValue("Content-Type")?.substringBefore(';')?.trim()
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
                ok(
                    checkName = ANIMATED_WEBP_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIMATED_GALLERY_ID.toString(),
                    message = "Verified bounded animated WebP range (${response.body.size} bytes)",
                )
            } else {
                degraded(
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
        return runStep(
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
            val contentType = response.headerValue("Content-Type")?.substringBefore(';')?.trim()
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
                ok(
                    checkName = ANIME_MP4_RANGE_STEP,
                    requestUrl = requestUrl,
                    itemCount = response.body.size,
                    samplePostId = ANIME_GALLERY_ID.toString(),
                    message = "Verified bounded MP4 range (${response.body.size} bytes)",
                )
            } else {
                degraded(
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

    private suspend fun runStep(
        checkName: String,
        initialRequestUrl: String? = null,
        requestUrl: () -> String? = { initialRequestUrl },
        block: suspend () -> ProviderProbeStepResult,
    ): ProviderProbeStepResult {
        var result: ProviderProbeStepResult? = null
        var failure: Throwable? = null
        val latencyMs = measureTimeMillis {
            try {
                result = block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failure = error
            }
        }
        val caught = failure
        if (caught != null) {
            return ProviderProbeStepResult(
                source = SourceKey.HITOMI,
                checkName = checkName,
                status = ProviderHealthStatus.FAILED,
                latencyMs = latencyMs,
                failureReason = caught.toFailureReason(),
                message = caught.message ?: caught::class.simpleName,
                checkedAtEpochMs = nowProvider(),
                requestUrl = requestUrl(),
            )
        }
        return requireNotNull(result).copy(latencyMs = latencyMs)
    }

    private fun ok(
        checkName: String,
        requestUrl: String,
        itemCount: Int? = null,
        samplePostId: String? = null,
        message: String,
    ): ProviderProbeStepResult {
        return result(
            checkName = checkName,
            status = ProviderHealthStatus.OK,
            requestUrl = requestUrl,
            itemCount = itemCount,
            samplePostId = samplePostId,
            message = message,
        )
    }

    private fun degraded(
        checkName: String,
        requestUrl: String,
        itemCount: Int? = null,
        samplePostId: String? = null,
        message: String,
    ): ProviderProbeStepResult {
        return result(
            checkName = checkName,
            status = ProviderHealthStatus.DEGRADED,
            requestUrl = requestUrl,
            itemCount = itemCount,
            samplePostId = samplePostId,
            message = message,
        )
    }

    private fun result(
        checkName: String,
        status: ProviderHealthStatus,
        requestUrl: String,
        itemCount: Int?,
        samplePostId: String?,
        message: String,
    ): ProviderProbeStepResult {
        return ProviderProbeStepResult(
            source = SourceKey.HITOMI,
            checkName = checkName,
            status = status,
            latencyMs = 0L,
            message = message,
            checkedAtEpochMs = nowProvider(),
            itemCount = itemCount,
            samplePostId = samplePostId,
            requestUrl = requestUrl,
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
        }.firstOrNull { location ->
            location.substringBefore('?').endsWith(".webp", ignoreCase = true)
        }
    }

    private fun SourceByteResponse.headerValue(name: String): String? {
        return headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }

    private fun SourceByteResponse.hasInitialContentRange(): Boolean {
        val value = headerValue("Content-Range") ?: return false
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
