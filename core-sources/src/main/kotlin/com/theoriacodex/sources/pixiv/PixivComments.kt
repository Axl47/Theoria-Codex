package com.theoriacodex.sources.pixiv

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.sources.common.booleanValue
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.stringValue

/**
 * Maps one `/v3/illust/comments` or `/v2/illust/comment/replies` response. The next page is the
 * absolute `next_url`, which the adapter requests as given. Pixiv reports the comment total only
 * on the illustration, so [total] comes from the caller.
 */
internal fun parsePixivCommentPage(root: JsonObject, total: Int?): CommentPage = CommentPage(
    comments = root.optionalJsonArray("comments").elementsOrEmpty().mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parsePixivComment)
    },
    total = total,
    nextPageToken = root.stringValue("next_url")?.trim()?.takeIf(String::isNotBlank),
)

private fun parsePixivComment(raw: JsonObject): PostComment? {
    val id = raw.longValue("id")?.toString() ?: return null
    val user = raw.optionalJsonObject("user")
    return PostComment(
        id = id,
        authorName = user?.stringValue("name")?.trim().orEmpty(),
        authorAvatarUrl = user?.optionalJsonObject("profile_image_urls")?.stringValue("medium")?.takeIf(String::isNotBlank),
        // A comment that is only a sticker has empty text.
        body = raw.stringValue("comment").orEmpty().trim(),
        createdAtEpochMs = parseOffsetDateTimeMs(raw.stringValue("date")),
        stampUrl = raw.optionalJsonObject("stamp")?.stringValue("stamp_url")?.takeIf(String::isNotBlank),
        hasReplies = raw.booleanValue("has_replies") == true,
    )
}

/** `total_comments` of a `/v1/illust/detail` response, or null when the response has none. */
internal fun parsePixivCommentTotal(detail: JsonObject): Int? =
    detail.optionalJsonObject("illust")?.longValue("total_comments")?.toInt()
