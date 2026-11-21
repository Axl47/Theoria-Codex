package com.theoriacodex.data.storage

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.VideoVariant
import com.theoriacodex.domain.model.contentHashOrNull
import com.theoriacodex.domain.model.withInferredGalleryLength
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.withRecoveredPixivTranslations
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CURRENT_POST_STORAGE_SCHEMA_VERSION: Int = 1

/**
 * Stable local-storage shape for a [Post].
 *
 * Persistence implementations serialize this record rather than the domain model, allowing the
 * domain to evolve without silently changing the durable schema. A null schema version denotes
 * the original pre-versioned JSON shape; unknown explicit versions fail closed in the codec.
 *
 * Room payloads, offline manifests, backups, and the search first-page cache all read and write it with
 * kotlinx.serialization.
 */
@Serializable
data class PostStorageRecord(
    @SerialName("source")
    val source: String = "",
    @SerialName("sourcePostId")
    val sourcePostId: String = "",
    @SerialName("previewUrl")
    val previewUrl: String? = null,
    @SerialName("previewLocalPath")
    val previewLocalPath: String? = null,
    @SerialName("previewMime")
    val previewMime: String? = null,
    @SerialName("previewProgressiveUrls")
    val previewProgressiveUrls: List<String>? = null,
    @SerialName("previewFallbackUrls")
    val previewFallbackUrls: List<String>? = null,
    @SerialName("previewIsAnimated")
    val previewIsAnimated: Boolean? = null,
    @SerialName("fullUrl")
    val fullUrl: String? = null,
    @SerialName("fullLocalPath")
    val fullLocalPath: String? = null,
    @SerialName("fullMime")
    val fullMime: String? = null,
    @SerialName("fullProgressiveUrls")
    val fullProgressiveUrls: List<String>? = null,
    @SerialName("fullFallbackUrls")
    val fullFallbackUrls: List<String>? = null,
    @SerialName("fullVideoVariants")
    val fullVideoVariants: List<VideoVariantRecord>? = null,
    @SerialName("fullIsAnimated")
    val fullIsAnimated: Boolean? = null,
    @SerialName("pageUrl")
    val pageUrl: String? = null,
    @SerialName("width")
    val width: Int? = null,
    @SerialName("height")
    val height: Int? = null,
    @SerialName("canonicalTags")
    val canonicalTags: List<String>? = null,
    @SerialName("rawTags")
    val rawTags: List<String>? = null,
    @SerialName("authorName")
    val authorName: String? = null,
    @SerialName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
    @SerialName("media")
    val media: List<ImageRefStorageRecord?>? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("creatorProfile")
    val creatorProfile: CreatorProfileStorageRecord? = null,
    @SerialName("durationMs")
    val durationMs: Long? = null,
    @SerialName("mediaCount")
    val mediaCount: Int? = null,
    // Written only when set, so records of every other post keep their exact stored form.
    @SerialName("galleryLengthUnknown")
    val galleryLengthUnknown: Boolean? = null,
    @SerialName("taxonomy")
    val taxonomy: List<PostTaxonomyTermStorageRecord?>? = null,
    @SerialName("creatorProfiles")
    val creatorProfiles: List<CreatorProfileStorageRecord?>? = null,
    // The site's own MD5 of the file. Optional: records written before it existed, and by sites that report none, omit it.
    @SerialName("contentHash")
    val contentHash: String? = null,
    // Null means the original pre-versioned payload; current writers always set the explicit version in the codec.
    @SerialName("schemaVersion")
    val schemaVersion: Int? = null,
)

@Serializable
data class PostTaxonomyTermStorageRecord(
    @SerialName("value")
    val value: String? = null,
    @SerialName("facet")
    val facet: String? = null,
    @SerialName("sourceNamespace")
    val sourceNamespace: String? = null,
    @SerialName("count")
    val count: Int? = null,
    @SerialName("translation")
    val translation: String? = null,
)

@Serializable
data class CreatorProfileStorageRecord(
    @SerialName("source")
    val source: String = "",
    @SerialName("displayName")
    val displayName: String = "",
    @SerialName("profileId")
    val profileId: String? = null,
    @SerialName("profileUrl")
    val profileUrl: String? = null,
    @SerialName("uploadsQuery")
    val uploadsQuery: String? = null,
)

@Serializable
data class VideoVariantRecord(
    @SerialName("url") val url: String? = null,
    @SerialName("height") val height: Int? = null,
    @SerialName("original") val original: Boolean = false,
    @SerialName("mime") val mime: String? = null,
)

private fun List<VideoVariantRecord>?.decodeVideoVariants(): List<VideoVariant> = orEmpty().mapNotNull {
    val url = it.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    VideoVariant(url, it.height?.takeIf { height -> height > 0 }, it.original, it.mime)
}.distinctBy(VideoVariant::url)

private fun List<VideoVariant>.encodeVideoVariants(): List<VideoVariantRecord> = map {
    VideoVariantRecord(it.url, it.height, it.original, it.mime)
}

@Serializable
data class ImageRefStorageRecord(
    @SerialName("url")
    val url: String? = null,
    @SerialName("localPath")
    val localPath: String? = null,
    @SerialName("mime")
    val mime: String? = null,
    @SerialName("progressiveUrls")
    val progressiveUrls: List<String>? = null,
    @SerialName("fallbackUrls")
    val fallbackUrls: List<String>? = null,
    @SerialName("videoVariants")
    val videoVariants: List<VideoVariantRecord>? = null,
    @SerialName("isAnimated")
    val isAnimated: Boolean? = null,
    @SerialName("thumbnailUrl")
    val thumbnailUrl: String? = null,
)

/** Pure conversion boundary shared by every durable Post store. */
object PostStorageCodec {
    fun encode(post: Post): PostStorageRecord {
        return PostStorageRecord(
            source = post.id.source.id,
            sourcePostId = post.id.sourcePostId,
            previewUrl = post.preview.url,
            previewLocalPath = post.preview.localPath,
            previewMime = post.preview.mime,
            previewProgressiveUrls = post.preview.progressiveUrls,
            previewFallbackUrls = post.preview.fallbackUrls,
            previewIsAnimated = post.preview.isAnimated,
            fullUrl = post.full?.url,
            fullLocalPath = post.full?.localPath,
            fullMime = post.full?.mime,
            fullProgressiveUrls = post.full?.progressiveUrls,
            fullFallbackUrls = post.full?.fallbackUrls,
            fullIsAnimated = post.full?.isAnimated,
            fullVideoVariants = post.full?.videoVariants?.encodeVideoVariants(),
            pageUrl = post.pageUrl,
            width = post.width,
            height = post.height,
            canonicalTags = post.canonicalTags,
            rawTags = post.rawTags,
            authorName = post.authorName,
            createdAtEpochMs = post.createdAtEpochMs,
            media = post.media.map(::encodeImageRef),
            title = post.title,
            creatorProfile = post.creatorProfile?.let(::encodeCreatorProfile),
            durationMs = post.durationMs,
            mediaCount = post.mediaCount,
            galleryLengthUnknown = post.galleryLengthUnknown.takeIf { it },
            taxonomy = post.taxonomy.map(::encodeTaxonomyTerm),
            creatorProfiles = post.creatorProfiles.map(::encodeCreatorProfile),
            contentHash = post.contentHash,
            schemaVersion = CURRENT_POST_STORAGE_SCHEMA_VERSION,
        )
    }

    fun decode(record: PostStorageRecord): Post? {
        if (record.schemaVersion != null && record.schemaVersion != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            return null
        }
        val source = SourceKey.parse(record.source) ?: return null
        val creator = record.creatorProfile?.toDomainOrNull()
        val canonicalTags = record.canonicalTags.orEmpty()
        return Post(
            id = PostId(source = source, sourcePostId = record.sourcePostId),
            preview = ImageRef(
                url = record.previewUrl,
                localPath = record.previewLocalPath,
                mime = record.previewMime,
                progressiveUrls = record.previewProgressiveUrls.orEmpty(),
                isAnimated = record.previewIsAnimated ?: false,
                fallbackUrls = record.previewFallbackUrls.orEmpty(),
            ).legacyCopiesIf(record.previewFallbackUrls == null, source),
            full = if (
                record.fullUrl == null &&
                record.fullLocalPath == null &&
                record.fullMime == null &&
                record.fullProgressiveUrls.isNullOrEmpty() &&
                record.fullFallbackUrls.isNullOrEmpty() &&
                record.fullIsAnimated != true
            ) {
                null
            } else {
                ImageRef(
                    url = record.fullUrl,
                    localPath = record.fullLocalPath,
                    mime = record.fullMime,
                    progressiveUrls = record.fullProgressiveUrls.orEmpty(),
                    isAnimated = record.fullIsAnimated ?: false,
                    videoVariants = record.fullVideoVariants.decodeVideoVariants(),
                    fallbackUrls = record.fullFallbackUrls.orEmpty(),
                ).legacyCopiesIf(record.fullFallbackUrls == null, source)
            },
            pageUrl = record.pageUrl,
            width = record.width,
            height = record.height,
            canonicalTags = canonicalTags,
            rawTags = record.rawTags.orEmpty(),
            authorName = record.authorName,
            createdAtEpochMs = record.createdAtEpochMs,
            media = record.media.orEmpty().mapNotNull { stored -> stored?.toDomain(source) },
            title = record.title,
            creatorProfile = creator,
            durationMs = record.durationMs,
            mediaCount = record.mediaCount,
            galleryLengthUnknown = record.galleryLengthUnknown == true,
            taxonomy = record.taxonomy
                ?.mapNotNull { stored -> stored?.toDomainOrNull() }
                ?: canonicalTags.map { value -> PostTaxonomyTerm(value = value) },
            creatorProfiles = record.creatorProfiles
                ?.mapNotNull { stored -> stored?.toDomainOrNull() }
                ?: listOfNotNull(creator),
            contentHash = contentHashOrNull(record.contentHash),
        ).withRecoveredPixivTranslations().withInferredGalleryLength()
    }

    private fun encodeImageRef(ref: ImageRef): ImageRefStorageRecord {
        return ImageRefStorageRecord(
            url = ref.url,
            localPath = ref.localPath,
            mime = ref.mime,
            progressiveUrls = ref.progressiveUrls,
            fallbackUrls = ref.fallbackUrls,
            isAnimated = ref.isAnimated,
            videoVariants = ref.videoVariants.encodeVideoVariants(),
            thumbnailUrl = ref.thumbnailUrl,
        )
    }

    private fun encodeCreatorProfile(profile: CreatorProfile): CreatorProfileStorageRecord {
        return CreatorProfileStorageRecord(
            source = profile.source.id,
            displayName = profile.displayName,
            profileId = profile.profileId,
            profileUrl = profile.profileUrl,
            uploadsQuery = profile.uploadsQuery,
        )
    }

    private fun encodeTaxonomyTerm(term: PostTaxonomyTerm): PostTaxonomyTermStorageRecord {
        return PostTaxonomyTermStorageRecord(
            value = term.value,
            facet = term.facet.name,
            sourceNamespace = term.sourceNamespace,
            count = term.count,
            translation = term.translation,
        )
    }
}

private fun ImageRefStorageRecord.toDomain(source: SourceKey): ImageRef {
    return ImageRef(
        url = url,
        localPath = localPath,
        mime = mime,
        progressiveUrls = progressiveUrls.orEmpty(),
        isAnimated = isAnimated ?: false,
        videoVariants = videoVariants.decodeVideoVariants(),
        thumbnailUrl = thumbnailUrl,
        fallbackUrls = fallbackUrls.orEmpty(),
    ).legacyCopiesIf(fallbackUrls == null, source)
}

private fun ImageRef.legacyCopiesIf(fieldAbsent: Boolean, source: SourceKey): ImageRef =
    if (fieldAbsent) withLegacyEquivalentCopies(source) else this

private fun CreatorProfileStorageRecord.toDomainOrNull(): CreatorProfile? {
    val sourceKey = SourceKey.parse(source) ?: return null
    return CreatorProfile(
        source = sourceKey,
        displayName = displayName,
        profileId = profileId,
        profileUrl = profileUrl,
        uploadsQuery = uploadsQuery,
    )
}

private fun PostTaxonomyTermStorageRecord.toDomainOrNull(): PostTaxonomyTerm? {
    val resolvedValue = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val resolvedFacet = facet.toSearchFacetOrNull() ?: return null
    return PostTaxonomyTerm(
        value = resolvedValue,
        facet = resolvedFacet,
        sourceNamespace = sourceNamespace?.trim()?.takeIf(String::isNotBlank),
        count = count?.takeIf { it >= 0 },
        translation = translation?.trim()?.takeIf(String::isNotBlank),
    )
}

private fun String?.toSearchFacetOrNull(): SearchFacet? {
    val normalized = this?.trim()?.takeIf(String::isNotBlank) ?: return SearchFacet.TAG
    return runCatching { SearchFacet.valueOf(normalized) }.getOrNull()
}
