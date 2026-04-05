package com.theoriacodex.domain.model

enum class SourceKey {
    PIXIV,
    GELBOORU,
    AIBOORU,
    NHENTAI,
    IWARA,
    RULE34XXX,
    RULE34PAHEAL,
    RULE34VIDEO,
    RULE34GEN,
}

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

data class ImageRef(
    val url: String?,
    val localPath: String?,
    val mime: String?,
)

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
)
