package com.theoriacodex.sources.pixiv

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.booleanValue
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.parseOffsetDateTimeMs
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.common.objects

internal fun parsePixivCommentPage(root: JsonObject, total: Int?): CommentPage = CommentPage(
    comments = root.objects("comments").mapNotNull(::parsePixivComment),
    total = total,
    nextPageToken = root.stringValue("next_url")?.trim()?.takeIf(String::isNotBlank),
)

private fun parsePixivComment(raw: JsonObject): PostComment? {
    val id = raw.longValue("id")?.toString() ?: return null
    val user = raw.optionalJsonObject("user")
    val userName = user?.stringValue("name")?.trim().orEmpty()
    val userId = user?.longValue("id")?.toString()
    return PostComment(
        id = id,
        authorName = userName,
        authorAvatarUrl = user?.optionalJsonObject("profile_image_urls")?.stringValue("medium")?.takeIf(String::isNotBlank),
        body = raw.stringValue("comment").orEmpty().trim(),
        createdAtEpochMs = parseOffsetDateTimeMs(raw.stringValue("date")),
        stampUrl = raw.optionalJsonObject("stamp")?.stringValue("stamp_url")?.takeIf(String::isNotBlank),
        hasReplies = raw.booleanValue("has_replies") == true,
        author = userId?.let { pixivUserProfile(it, userName) },
    )
}

internal fun parsePixivCommentTotal(detail: JsonObject): Int? =
    detail.optionalJsonObject("illust")?.longValue("total_comments")?.toInt()

internal fun pixivUserProfile(userId: String?, name: String?): CreatorProfile? {
    val displayName = name?.takeIf(String::isNotBlank) ?: userId ?: return null
    return CreatorProfile(
        source = SourceKey.PIXIV,
        displayName = displayName,
        profileId = userId,
        profileUrl = userId?.let { "https://www.pixiv.net/en/users/$it" },
        uploadsQuery = userId,
    )
}
