package com.theoriacodex.sources.nhentai

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.stringValue
import com.theoriacodex.sources.common.objects

internal const val NHENTAI_COMMENTS_PER_PAGE = 50

internal fun parseNhentaiCommentPage(root: JsonObject, pageIndex: Int): CommentPage {
    val comments = root.objects("result").mapNotNull(::parseNhentaiComment)
    val totalPages = root.get("num_pages").asIntOrNull()
    return CommentPage(
        comments = comments,
        total = root.intValue("total"),
        nextPageToken = (pageIndex + 1).toString().takeIf { totalPages != null && pageIndex < totalPages },
    )
}

private fun parseNhentaiComment(raw: JsonObject): PostComment? {
    val id = raw.longValue("id")?.toString() ?: return null
    val poster = raw.optionalJsonObject("poster")
    val username = poster?.stringValue("username")?.trim().orEmpty()
    val userId = poster?.longValue("id")?.toString()
    return PostComment(
        id = id,
        authorName = username,
        authorAvatarUrl = poster?.stringValue("avatar_url")?.trim()?.takeIf(String::isNotBlank)
            ?.let { "$NHENTAI_IMAGES_BASE/${it.trimStart('/')}" },
        body = raw.stringValue("body").orEmpty().trim(),
        createdAtEpochMs = raw.longValue("post_date")?.times(MS_PER_SECOND),
        author = userId?.let {
            val slug = poster.stringValue("slug")?.trim()?.takeIf(String::isNotBlank)
            CreatorProfile(
                source = SourceKey.NHENTAI,
                displayName = username.ifBlank { it },
                profileId = it,
                profileUrl = "https://nhentai.net/users/$it${slug?.let { s -> "/$s" }.orEmpty()}",
                uploadsQuery = null,
            )
        },
    )
}

private const val MS_PER_SECOND = 1_000L
