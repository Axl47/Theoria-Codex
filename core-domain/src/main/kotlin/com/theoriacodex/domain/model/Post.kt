package com.theoriacodex.domain.model

enum class SourceKey {
    PIXIV,
    GELBOORU,
    AIBOORU,
}

data class PostId(
    val source: SourceKey,
    val sourcePostId: String,
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
)
