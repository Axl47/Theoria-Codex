package com.theoriacodex.data.storage

import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey

const val CURRENT_POST_STORAGE_SCHEMA_VERSION: Int = 1

/**
 * Stable local-storage shape for a [Post].
 *
 * Persistence implementations serialize this record rather than the domain model, allowing the
 * domain to evolve without silently changing the durable schema. A null schema version denotes
 * the original pre-versioned JSON shape; unknown explicit versions fail closed in the codec.
 */
data class PostStorageRecord(
    @field:SerializedName("source")
    val source: String = "",
    @field:SerializedName("sourcePostId")
    val sourcePostId: String = "",
    @field:SerializedName("previewUrl")
    val previewUrl: String? = null,
    @field:SerializedName("previewLocalPath")
    val previewLocalPath: String? = null,
    @field:SerializedName("previewMime")
    val previewMime: String? = null,
    @field:SerializedName("previewProgressiveUrls")
    val previewProgressiveUrls: List<String>? = null,
    @field:SerializedName("previewIsAnimated")
    val previewIsAnimated: Boolean? = null,
    @field:SerializedName("fullUrl")
    val fullUrl: String? = null,
    @field:SerializedName("fullLocalPath")
    val fullLocalPath: String? = null,
    @field:SerializedName("fullMime")
    val fullMime: String? = null,
    @field:SerializedName("fullProgressiveUrls")
    val fullProgressiveUrls: List<String>? = null,
    @field:SerializedName("fullIsAnimated")
    val fullIsAnimated: Boolean? = null,
    @field:SerializedName("pageUrl")
    val pageUrl: String? = null,
    @field:SerializedName("width")
    val width: Int? = null,
    @field:SerializedName("height")
    val height: Int? = null,
    @field:SerializedName("canonicalTags")
    val canonicalTags: List<String>? = null,
    @field:SerializedName("rawTags")
    val rawTags: List<String>? = null,
    @field:SerializedName("authorName")
    val authorName: String? = null,
    @field:SerializedName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
    @field:SerializedName("media")
    val media: List<ImageRefStorageRecord?>? = null,
    @field:SerializedName("title")
    val title: String? = null,
    @field:SerializedName("creatorProfile")
    val creatorProfile: CreatorProfileStorageRecord? = null,
    @field:SerializedName("durationMs")
    val durationMs: Long? = null,
    @field:SerializedName("mediaCount")
    val mediaCount: Int? = null,
    @field:SerializedName("taxonomy")
    val taxonomy: List<PostTaxonomyTermStorageRecord?>? = null,
    @field:SerializedName("creatorProfiles")
    val creatorProfiles: List<CreatorProfileStorageRecord?>? = null,
    @field:SerializedName("schemaVersion")
    // Gson may invoke the generated no-arg constructor. Null must continue to mean the original
    // pre-versioned payload; current writers always set the explicit version in the codec.
    val schemaVersion: Int? = null,
)

data class PostTaxonomyTermStorageRecord(
    @field:SerializedName("value")
    val value: String? = null,
    @field:SerializedName("facet")
    val facet: String? = null,
    @field:SerializedName("sourceNamespace")
    val sourceNamespace: String? = null,
)

data class CreatorProfileStorageRecord(
    @field:SerializedName("source")
    val source: String = "",
    @field:SerializedName("displayName")
    val displayName: String = "",
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("profileUrl")
    val profileUrl: String? = null,
    @field:SerializedName("uploadsQuery")
    val uploadsQuery: String? = null,
)

data class ImageRefStorageRecord(
    @field:SerializedName("url")
    val url: String? = null,
    @field:SerializedName("localPath")
    val localPath: String? = null,
    @field:SerializedName("mime")
    val mime: String? = null,
    @field:SerializedName("progressiveUrls")
    val progressiveUrls: List<String>? = null,
    @field:SerializedName("isAnimated")
    val isAnimated: Boolean? = null,
)

/** Pure conversion boundary shared by every durable Post store. */
object PostStorageCodec {
    fun encode(post: Post): PostStorageRecord {
        return PostStorageRecord(
            source = post.id.source.name,
            sourcePostId = post.id.sourcePostId,
            previewUrl = post.preview.url,
            previewLocalPath = post.preview.localPath,
            previewMime = post.preview.mime,
            previewProgressiveUrls = post.preview.progressiveUrls,
            previewIsAnimated = post.preview.isAnimated,
            fullUrl = post.full?.url,
            fullLocalPath = post.full?.localPath,
            fullMime = post.full?.mime,
            fullProgressiveUrls = post.full?.progressiveUrls,
            fullIsAnimated = post.full?.isAnimated,
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
            schemaVersion = CURRENT_POST_STORAGE_SCHEMA_VERSION,
        )
    }

    fun decode(record: PostStorageRecord): Post? {
        if (record.schemaVersion != null && record.schemaVersion != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            return null
        }
        val source = record.source.toSourceKeyOrNull() ?: return null
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
            ),
            full = if (
                record.fullUrl == null &&
                record.fullLocalPath == null &&
                record.fullMime == null &&
                record.fullProgressiveUrls.isNullOrEmpty() &&
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
                )
            },
            pageUrl = record.pageUrl,
            width = record.width,
            height = record.height,
            canonicalTags = canonicalTags,
            rawTags = record.rawTags.orEmpty(),
            authorName = record.authorName,
            createdAtEpochMs = record.createdAtEpochMs,
            media = record.media.orEmpty().mapNotNull { stored -> stored?.toDomain() },
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
        )
    }

    private fun encodeImageRef(ref: ImageRef): ImageRefStorageRecord {
        return ImageRefStorageRecord(
            url = ref.url,
            localPath = ref.localPath,
            mime = ref.mime,
            progressiveUrls = ref.progressiveUrls,
            isAnimated = ref.isAnimated,
        )
    }

    private fun encodeCreatorProfile(profile: CreatorProfile): CreatorProfileStorageRecord {
        return CreatorProfileStorageRecord(
            source = profile.source.name,
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
        )
    }
}

private fun ImageRefStorageRecord.toDomain(): ImageRef {
    return ImageRef(
        url = url,
        localPath = localPath,
        mime = mime,
        progressiveUrls = progressiveUrls.orEmpty(),
        isAnimated = isAnimated ?: false,
    )
}

private fun CreatorProfileStorageRecord.toDomainOrNull(): CreatorProfile? {
    val sourceKey = source.toSourceKeyOrNull() ?: return null
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
    )
}

private fun String.toSourceKeyOrNull(): SourceKey? {
    return trim()
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
}

private fun String?.toSearchFacetOrNull(): SearchFacet? {
    val normalized = this?.trim()?.takeIf(String::isNotBlank) ?: return SearchFacet.TAG
    return runCatching { SearchFacet.valueOf(normalized) }.getOrNull()
}
