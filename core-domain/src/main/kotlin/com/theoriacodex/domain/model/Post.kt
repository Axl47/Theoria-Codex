package com.theoriacodex.domain.model

/** Canonical wire MIME emitted for Pixiv Ugoira animation archives. */
const val PIXIV_UGOIRA_MIME: String = "image/ugoira"

data class PostId(
    val source: SourceKey,
    val sourcePostId: String,
)

data class CreatorProfile(
    val source: SourceKey,
    val displayName: String,
    val profileId: String? = null,
    val profileUrl: String? = null,
    val uploadsQuery: String? = null,
)

/** Alternate full-video renditions of one media item; previews never belong here. */
data class VideoVariant(val url: String, val height: Int? = null, val original: Boolean = false, val mime: String? = null)

enum class VideoQuality { AUTO, DATA_SAVER, BEST }

data class ImageRef(
    val url: String?,
    val localPath: String?,
    val mime: String?,
    /** Lighter renditions of [url], lightest first; shown before [url] and then replaced by it. */
    val progressiveUrls: List<String> = emptyList(),
    val isAnimated: Boolean = false,
    val videoVariants: List<VideoVariant> = emptyList(),
    /** Small stand-in for choosing a page; never shown as the page itself. */
    val thumbnailUrl: String? = null,
    /** Equivalent copies of [url] (mirrors, other formats, other shards); tried only when it fails, never shown as a lighter step. */
    val fallbackUrls: List<String> = emptyList(),
)

data class PostTaxonomyTerm(
    val value: String,
    val facet: SearchFacet = SearchFacet.TAG,
    val sourceNamespace: String? = null,
    val count: Int? = null,
    /** Provider-supplied translation of [value] (Pixiv's English name). Shown and learned, never searched. */
    val translation: String? = null,
) {
    fun toSearchTerm(): SearchTerm {
        return SearchTerm(
            value = value,
            facet = facet,
            sourceNamespace = sourceNamespace,
        )
    }
}

data class Post(
    val id: PostId,
    val preview: ImageRef,
    val full: ImageRef?,
    val media: List<ImageRef> = emptyList(),
    val pageUrl: String?,
    val width: Int?,
    val height: Int?,
    val canonicalTags: List<String>,
    val rawTags: List<String>,
    val authorName: String?,
    val createdAtEpochMs: Long?,
    val title: String? = null,
    val creatorProfile: CreatorProfile? = null,
    val durationMs: Long? = null,
    val mediaCount: Int? = null,
    val taxonomy: List<PostTaxonomyTerm> = canonicalTags.map { value ->
        PostTaxonomyTerm(value = value)
    },
    val creatorProfiles: List<CreatorProfile> = listOfNotNull(creatorProfile),
)
