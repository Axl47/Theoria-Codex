package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.media.mimeFromFileExt
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Maps parsed gallery metadata to sparse or resolved posts and recovers provider media URLs. */
internal class HitomiPostMapper(
    private val mediaUrlResolver: HitomiMediaUrlResolver,
    private val mediaCandidates: suspend (HitomiMediaFile) -> List<HitomiMediaCandidate>,
) {
    suspend fun toPost(metadata: ParsedHitomiGallery, sparse: Boolean): Post {
        val files = metadata.files
        val posterFile = if (metadata.hasPlayableVideo) files.lastOrNull() else files.firstOrNull()
        val filesToResolve = if (sparse || metadata.hasPlayableVideo) listOfNotNull(posterFile) else files
        val resolvedCandidates = filesToResolve.associateWith { file -> candidatesFor(file) }
        val poster = posterFile?.let { file ->
            imageRef(file, resolvedCandidates[file].orEmpty(), metadata.animated, preview = true)
        } ?: EMPTY_IMAGE_REF
        val media = mediaRefs(metadata, sparse, resolvedCandidates)
        val creatorProfiles = metadata.creators
        val primaryCreator = creatorProfiles.firstOrNull()
        val canonicalTags = metadata.taxonomy
            .filter { term -> term.facet == SearchFacet.TAG }
            .map(PostTaxonomyTerm::value)
            .distinct()
        val galleryPath = metadata.galleryPath
        val width = posterFile?.width
        val height = posterFile?.height

        return Post(
            id = PostId(SourceKey.HITOMI, metadata.id.toString()),
            preview = poster,
            full = media.firstOrNull(),
            media = media,
            pageUrl = galleryPath?.let { path -> "https://hitomi.la/${path.trimStart('/')}" },
            width = width,
            height = height,
            canonicalTags = canonicalTags,
            rawTags = canonicalTags,
            authorName = primaryCreator?.displayName,
            createdAtEpochMs = metadata.createdAtEpochMs,
            title = metadata.title,
            creatorProfile = primaryCreator,
            mediaCount = if (metadata.hasPlayableVideo) 1 else files.size,
            taxonomy = metadata.taxonomy,
            creatorProfiles = creatorProfiles,
        )
    }

    private fun mediaRefs(
        metadata: ParsedHitomiGallery,
        sparse: Boolean,
        candidates: Map<ParsedHitomiFile, List<HitomiMediaCandidate>>,
    ): List<ImageRef> {
        val video = metadata.videoFilename?.takeIf { metadata.hasPlayableVideo }?.let { filename ->
            ImageRef(
                url = hitomiVideoUrl(filename),
                localPath = null,
                mime = "video/mp4",
                progressiveUrls = emptyList(),
            )
        }
        if (sparse) return listOfNotNull(video).takeIf { metadata.hasPlayableVideo }.orEmpty()
        if (video != null) return listOf(video)
        return metadata.files.map { file ->
            imageRef(file, candidates[file].orEmpty(), metadata.animated, preview = false)
        }
    }

    private suspend fun candidatesFor(file: ParsedHitomiFile): List<HitomiMediaCandidate> {
        val candidates = try {
            mediaCandidates(
                HitomiMediaFile(
                    hash = file.hash,
                    name = file.name,
                    hasAvif = file.hasAvif,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SourceAdapterException) {
            throw error
        } catch (error: IOException) {
            throw SourceAdapterException(
                reason = SourceFailureReason.NETWORK,
                message = "Hitomi media URL configuration request failed",
                cause = error,
            )
        } catch (error: Exception) {
            throw SourceAdapterException(
                reason = SourceFailureReason.PARSE,
                message = "Hitomi media URL configuration was invalid",
                cause = error,
            )
        }
        if (candidates.isEmpty()) {
            throw sourceParseFailure("Hitomi media URL resolver returned no candidates")
        }
        return candidates
    }

    private fun imageRef(
        file: ParsedHitomiFile,
        candidates: List<HitomiMediaCandidate>,
        animated: Boolean,
        preview: Boolean,
    ): ImageRef {
        val first = if (preview || animated) {
            candidates.firstOrNull { candidate -> candidate.format == HitomiMediaFormat.WEBP }
                ?: candidates.firstOrNull { candidate -> candidate.format == HitomiMediaFormat.ORIGINAL }
                ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
        val orderedCandidates = first
            ?.let { preferred -> candidates.preferFormat(preferred.format) }
            .orEmpty()
        return ImageRef(
            url = first?.url,
            localPath = null,
            mime = first?.mime ?: mimeFromFileExt(file.name.substringAfterLast('.', "")),
            progressiveUrls = orderedCandidates.map(HitomiMediaCandidate::url).distinct(),
            isAnimated = animated,
        )
    }

    private fun ImageRef.hitomiRecoveryIdentityOrNull(): HitomiMediaRecoveryIdentity? {
        val locations = buildList {
            url?.takeIf(String::isNotBlank)?.let(::add)
            addAll(progressiveUrls.filter(String::isNotBlank))
        }.distinct()
        val failed = locations.firstOrNull()?.toHitomiCandidateIdentityOrNull() ?: return null
        val parsedLocations = locations.mapNotNull { location ->
            location.toHitomiCandidateIdentityOrNull()
        }
        val originalExtension = parsedLocations
            .firstOrNull { candidate -> candidate.format == HitomiMediaFormat.ORIGINAL }
            ?.extension
            ?: failed.extension
        return HitomiMediaRecoveryIdentity(
            hash = failed.hash,
            originalExtension = originalExtension,
            hasAvif = parsedLocations.any { candidate -> candidate.format == HitomiMediaFormat.AVIF },
            configurationVersion = failed.configurationVersion,
            failedFormat = failed.format,
        )
    }

    private fun String.toHitomiCandidateIdentityOrNull(): HitomiCandidateIdentity? {
        val parsed = runCatching { URI(this) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        val format = when {
            HITOMI_AVIF_HOST.matches(host) -> HitomiMediaFormat.AVIF
            HITOMI_WEBP_HOST.matches(host) -> HitomiMediaFormat.WEBP
            HITOMI_ORIGINAL_HOST.matches(host) -> HitomiMediaFormat.ORIGINAL
            else -> return null
        }
        val segments = parsed.path
            ?.split('/')
            ?.filter(String::isNotBlank)
            .orEmpty()
        val baseStart = if (format == HitomiMediaFormat.ORIGINAL) {
            if (segments.firstOrNull() != "images") return null
            1
        } else {
            0
        }
        if (segments.size - baseStart < 3) return null
        val fileName = segments.last()
        val hash = fileName.substringBeforeLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (!HITOMI_MEDIA_HASH.matches(hash) || !HITOMI_MEDIA_EXTENSION.matches(extension)) return null
        val pathNumberIndex = segments.lastIndex - 1
        if (segments[pathNumberIndex].toIntOrNull() == null) return null
        val baseSegments = segments.subList(baseStart, pathNumberIndex)
        if (baseSegments.isEmpty()) return null
        return HitomiCandidateIdentity(
            hash = hash,
            extension = extension,
            configurationVersion = baseSegments.joinToString("/"),
            format = format,
        )
    }

    private fun List<HitomiMediaCandidate>.preferFormat(
        format: HitomiMediaFormat,
    ): List<HitomiMediaCandidate> {
        return filter { candidate -> candidate.format == format } +
            filterNot { candidate -> candidate.format == format }
    }

    suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? {
        if (post.id.source != SourceKey.HITOMI) return null
        val identity = failedMedia.hitomiRecoveryIdentityOrNull() ?: return null
        val refreshedCandidates = mediaUrlResolver.refreshCandidates(
            file = HitomiMediaFile(
                hash = identity.hash,
                name = "${identity.hash}.${identity.originalExtension}",
                hasAvif = identity.hasAvif,
            ),
            failedConfigurationVersion = identity.configurationVersion,
        )
        if (refreshedCandidates.isEmpty()) return null

        var replaced = false
        fun recover(ref: ImageRef): ImageRef {
            val refIdentity = ref.hitomiRecoveryIdentityOrNull() ?: return ref
            if (refIdentity.hash != identity.hash) return ref
            val refCandidates = refreshedCandidates.preferFormat(refIdentity.failedFormat)
            if (refCandidates.isEmpty()) return ref
            replaced = true
            return ref.copy(
                url = refCandidates.first().url,
                mime = refCandidates.first().mime,
                progressiveUrls = refCandidates.map(HitomiMediaCandidate::url).distinct(),
            )
        }

        val recovered = post.copy(
            preview = recover(post.preview),
            full = post.full?.let(::recover),
            media = post.media.map(::recover),
        )
        return recovered.takeIf { replaced }
    }

    private data class HitomiCandidateIdentity(
        val hash: String,
        val extension: String,
        val configurationVersion: String,
        val format: HitomiMediaFormat,
    )

    private data class HitomiMediaRecoveryIdentity(
        val hash: String,
        val originalExtension: String,
        val hasAvif: Boolean,
        val configurationVersion: String,
        val failedFormat: HitomiMediaFormat,
    )

    private fun sourceParseFailure(message: String): SourceAdapterException = SourceAdapterException(
        reason = SourceFailureReason.PARSE,
        message = message,
    )

    private companion object {
        private val HITOMI_AVIF_HOST = Regex("""a[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_WEBP_HOST = Regex("""w[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_ORIGINAL_HOST = Regex("""[12]\.gold-usergeneratedcontent\.net""")
        private val HITOMI_MEDIA_HASH = Regex("""[0-9a-f]{64}""")
        private val HITOMI_MEDIA_EXTENSION = Regex("""[a-z0-9]{1,10}""")
        private val EMPTY_IMAGE_REF = ImageRef(url = null, localPath = null, mime = null)
        private fun hitomiVideoUrl(filename: String): String {
            val encoded = URLEncoder.encode(filename, Charsets.UTF_8.name()).replace("+", "%20")
            return "https://streaming.gold-usergeneratedcontent.net/videos/$encoded"
        }
    }
}
