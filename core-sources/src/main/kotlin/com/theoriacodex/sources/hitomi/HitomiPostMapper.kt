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
import kotlinx.coroutines.CancellationException

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
            val candidates = resolvedCandidates[file].orEmpty()
            val fullSize = imageRef(file, candidates, metadata.animated, preview = true)
            if (metadata.animated) fullSize else fullSize.thumbnailFirst(thumbnailUrls(file, candidates, HitomiThumbnail.CARD))
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
            )
        }
        if (sparse) return listOfNotNull(video).takeIf { metadata.hasPlayableVideo }.orEmpty()
        if (video != null) return listOf(video)
        return metadata.files.map { file ->
            val fileCandidates = candidates[file].orEmpty()
            imageRef(file, fileCandidates, metadata.animated, preview = false).copy(
                thumbnailUrl = thumbnailUrls(file, fileCandidates, HitomiThumbnail.PAGE).firstOrNull()
                    .takeUnless { metadata.animated },
            )
        }
    }

    private fun thumbnailUrls(
        file: ParsedHitomiFile,
        candidates: List<HitomiMediaCandidate>,
        rendition: HitomiThumbnail,
    ): List<String> {
        val primaryShard = candidates.firstOrNull { !it.isAlternateShard }?.shard ?: return emptyList()
        return hitomiThumbnailUrls(file.hash, primaryShard, rendition)
    }

    private fun ImageRef.thumbnailFirst(thumbnails: List<String>): ImageRef {
        val thumbnail = thumbnails.firstOrNull() ?: return this
        return copy(
            url = thumbnail,
            mime = "image/webp",
            fallbackUrls = (thumbnails.drop(1) + listOfNotNull(url) + fallbackUrls).distinct(),
        )
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
            throw hitomiParseFailure("Hitomi media URL resolver returned no candidates")
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
            fallbackUrls = orderedCandidates.map(HitomiMediaCandidate::url).distinct(),
            isAnimated = animated,
        )
    }

    private fun ImageRef.hitomiRecoveryIdentityOrNull(): HitomiMediaRecoveryIdentity? {
        val locations = buildList {
            url?.takeIf(String::isNotBlank)?.let(::add)
            addAll(fallbackUrls.filter(String::isNotBlank))
        }.distinct()
        val parsedLocations = locations.mapNotNull { location ->
            HitomiMediaLocation.parse(location)
        }
        val failed = parsedLocations.firstOrNull() ?: return null
        val originalExtension = parsedLocations
            .firstOrNull { candidate -> candidate.format == HitomiMediaFormat.ORIGINAL }
            ?.extension
            ?: failed.extension
        return HitomiMediaRecoveryIdentity(
            hash = failed.hash,
            originalExtension = originalExtension,
            hasAvif = parsedLocations.any { candidate -> candidate.format == HitomiMediaFormat.AVIF },
            configurationVersion = failed.version,
            failedFormat = failed.format,
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
            val thumbnails = (listOfNotNull(ref.url) + ref.fallbackUrls).filter(::isHitomiThumbnailUrl)
            return ref.copy(
                url = refCandidates.first().url,
                mime = refCandidates.first().mime,
                fallbackUrls = refCandidates.map(HitomiMediaCandidate::url).distinct(),
            ).thumbnailFirst(thumbnails)
        }

        val recovered = post.copy(
            preview = recover(post.preview),
            full = post.full?.let(::recover),
            media = post.media.map(::recover),
        )
        return recovered.takeIf { replaced }
    }

    private data class HitomiMediaRecoveryIdentity(
        val hash: String,
        val originalExtension: String,
        val hasAvif: Boolean,
        val configurationVersion: String,
        val failedFormat: HitomiMediaFormat,
    )

    private companion object {
        private val EMPTY_IMAGE_REF = ImageRef(url = null, localPath = null, mime = null)
    }
}
