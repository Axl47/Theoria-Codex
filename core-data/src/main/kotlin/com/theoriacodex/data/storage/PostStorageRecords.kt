package com.theoriacodex.data.storage

import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.VideoVariant
import com.theoriacodex.domain.model.contentHashOrNull
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
 * Room payloads, offline manifests, and the search first-page cache use kotlinx.serialization. Backups still
 * decode this graph with Gson, so every record here keeps both names until backups move too.
 */
@Serializable
data class PostStorageRecord(
    @field:SerializedName("source")
    @SerialName("source")
    val source: String = "",
    @field:SerializedName("sourcePostId")
    @SerialName("sourcePostId")
    val sourcePostId: String = "",
    @field:SerializedName("previewUrl")
    @SerialName("previewUrl")
    val previewUrl: String? = null,
    @field:SerializedName("previewLocalPath")
    @SerialName("previewLocalPath")
    val previewLocalPath: String? = null,
    @field:SerializedName("previewMime")
    @SerialName("previewMime")
    val previewMime: String? = null,
    @field:SerializedName("previewProgressiveUrls")
    @SerialName("previewProgressiveUrls")
    val previewProgressiveUrls: List<String>? = null,
    @field:SerializedName("previewFallbackUrls")
    @SerialName("previewFallbackUrls")
    val previewFallbackUrls: List<String>? = null,
    @field:SerializedName("previewIsAnimated")
    @SerialName("previewIsAnimated")
    val previewIsAnimated: Boolean? = null,
    @field:SerializedName("fullUrl")
    @SerialName("fullUrl")
    val fullUrl: String? = null,
    @field:SerializedName("fullLocalPath")
    @SerialName("fullLocalPath")
    val fullLocalPath: String? = null,
    @field:SerializedName("fullMime")
    @SerialName("fullMime")
    val fullMime: String? = null,
    @field:SerializedName("fullProgressiveUrls")
    @SerialName("fullProgressiveUrls")
    val fullProgressiveUrls: List<String>? = null,
    @field:SerializedName("fullFallbackUrls")
    @SerialName("fullFallbackUrls")
    val fullFallbackUrls: List<String>? = null,
    @field:SerializedName("fullVideoVariants")
    @SerialName("fullVideoVariants")
    val fullVideoVariants: List<VideoVariantRecord>? = null,
    @field:SerializedName("fullIsAnimated")
    @SerialName("fullIsAnimated")
    val fullIsAnimated: Boolean? = null,
    @field:SerializedName("pageUrl")
    @SerialName("pageUrl")
    val pageUrl: String? = null,
    @field:SerializedName("width")
    @SerialName("width")
    val width: Int? = null,
    @field:SerializedName("height")
    @SerialName("height")
    val height: Int? = null,
    @field:SerializedName("canonicalTags")
    @SerialName("canonicalTags")
    val canonicalTags: List<String>? = null,
    @field:SerializedName("rawTags")
    @SerialName("rawTags")
    val rawTags: List<String>? = null,
    @field:SerializedName("authorName")
    @SerialName("authorName")
    val authorName: String? = null,
    @field:SerializedName("createdAtEpochMs")
    @SerialName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
    @field:SerializedName("media")
    @SerialName("media")
    val media: List<ImageRefStorageRecord?>? = null,
    @field:SerializedName("title")
    @SerialName("title")
    val title: String? = null,
    @field:SerializedName("creatorProfile")
    @SerialName("creatorProfile")
    val creatorProfile: CreatorProfileStorageRecord? = null,
    @field:SerializedName("durationMs")
    @SerialName("durationMs")
    val durationMs: Long? = null,
    @field:SerializedName("mediaCount")
    @SerialName("mediaCount")
    val mediaCount: Int? = null,
    @field:SerializedName("taxonomy")
    @SerialName("taxonomy")
    val taxonomy: List<PostTaxonomyTermStorageRecord?>? = null,
    @field:SerializedName("creatorProfiles")
    @SerialName("creatorProfiles")
    val creatorProfiles: List<CreatorProfileStorageRecord?>? = null,
    // The site's own MD5 of the file. Optional: records written before it existed, and by sites that report none, omit it.
    @field:SerializedName("contentHash")
    @SerialName("contentHash")
    val contentHash: String? = null,
    // Null means the original pre-versioned payload; current writers always set the explicit version in the codec.
    @field:SerializedName("schemaVersion")
    @SerialName("schemaVersion")
    val schemaVersion: Int? = null,
)

@Serializable
data class PostTaxonomyTermStorageRecord(
    @field:SerializedName("value")
    @SerialName("value")
    val value: String? = null,
    @field:SerializedName("facet")
    @SerialName("facet")
    val facet: String? = null,
    @field:SerializedName("sourceNamespace")
    @SerialName("sourceNamespace")
    val sourceNamespace: String? = null,
    @field:SerializedName("count")
    @SerialName("count")
    val count: Int? = null,
    @field:SerializedName("translation")
    @SerialName("translation")
    val translation: String? = null,
)

@Serializable
data class CreatorProfileStorageRecord(
    @field:SerializedName("source")
    @SerialName("source")
    val source: String = "",
    @field:SerializedName("displayName")
    @SerialName("displayName")
    val displayName: String = "",
    @field:SerializedName("profileId")
    @SerialName("profileId")
    val profileId: String? = null,
    @field:SerializedName("profileUrl")
    @SerialName("profileUrl")
    val profileUrl: String? = null,
    @field:SerializedName("uploadsQuery")
    @SerialName("uploadsQuery")
    val uploadsQuery: String? = null,
)

@Serializable
data class VideoVariantRecord(
    @field:SerializedName("url") @SerialName("url") val url: String? = null,
    @field:SerializedName("height") @SerialName("height") val height: Int? = null,
    @field:SerializedName("original") @SerialName("original") val original: Boolean = false,
    @field:SerializedName("mime") @SerialName("mime") val mime: String? = null,
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
    @field:SerializedName("url")
    @SerialName("url")
    val url: String? = null,
    @field:SerializedName("localPath")
    @SerialName("localPath")
    val localPath: String? = null,
    @field:SerializedName("mime")
    @SerialName("mime")
    val mime: String? = null,
    @field:SerializedName("progressiveUrls")
    @SerialName("progressiveUrls")
    val progressiveUrls: List<String>? = null,
    @field:SerializedName("fallbackUrls")
    @SerialName("fallbackUrls")
    val fallbackUrls: List<String>? = null,
    @field:SerializedName("videoVariants")
    @SerialName("videoVariants")
    val videoVariants: List<VideoVariantRecord>? = null,
    @field:SerializedName("isAnimated")
    @SerialName("isAnimated")
    val isAnimated: Boolean? = null,
    @field:SerializedName("thumbnailUrl")
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
            taxonomy = record.taxonomy
                ?.mapNotNull { stored -> stored?.toDomainOrNull() }
                ?: canonicalTags.map { value -> PostTaxonomyTerm(value = value) },
            creatorProfiles = record.creatorProfiles
                ?.mapNotNull { stored -> stored?.toDomainOrNull() }
                ?: listOfNotNull(creator),
            contentHash = contentHashOrNull(record.contentHash),
        ).withRecoveredPixivTranslations()
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
