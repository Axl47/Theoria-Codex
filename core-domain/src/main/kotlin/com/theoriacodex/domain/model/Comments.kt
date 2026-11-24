package com.theoriacodex.domain.model

data class CommentHint(val count: Int?) {
    val hasComments: Boolean get() = count != 0

    companion object {
        val None = CommentHint(0)
        val Some = CommentHint(null)
    }
}

enum class CommentOrder {
    OLDEST,
    NEWEST,
    ;

    fun toggled(): CommentOrder = if (this == OLDEST) NEWEST else OLDEST
}

data class PostComment(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val body: String,
    val createdAtEpochMs: Long?,
    val stampUrl: String? = null,
    val hasReplies: Boolean = false,
    val author: CreatorProfile? = null,
)

data class CommentPage(
    val comments: List<PostComment>,
    val total: Int?,
    val nextPageToken: String?,
)
