package com.theoriacodex.data.storage

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
    val source: String,
    val sourcePostId: String,
    val previewUrl: String?,
    val previewLocalPath: String?,
    val previewMime: String?,
    val previewProgressiveUrls: List<String>? = null,
    val previewIsAnimated: Boolean? = null,
    val fullUrl: String?,
    val fullLocalPath: String?,
    val fullMime: String?,
    val fullProgressiveUrls: List<String>? = null,
    val fullIsAnimated: Boolean? = null,
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>? = null,
    val rawTags: List<String>? = null,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val media: List<ImageRefStorageRecord?>? = null,
    val title: String? = null,
    val creatorProfile: CreatorProfileStorageRecord? = null,
    val durationMs: Long? = null,
    val mediaCount: Int? = null,
    val taxonomy: List<PostTaxonomyTermStorageRecord?>? = null,
    val creatorProfiles: List<CreatorProfileStorageRecord?>? = null,
    val schemaVersion: Int? = CURRENT_POST_STORAGE_SCHEMA_VERSION,
)

data class PostTaxonomyTermStorageRecord(
    val value: String? = null,
    val facet: String? = null,
    val sourceNamespace: String? = null,
)

data class CreatorProfileStorageRecord(
    val source: String,
    val displayName: String,
    val profileId: String? = null,
    val profileUrl: String? = null,
    val uploadsQuery: String? = null,
)

data class ImageRefStorageRecord(
    val url: String?,
    val localPath: String?,
    val mime: String?,
    val progressiveUrls: List<String>? = null,
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
