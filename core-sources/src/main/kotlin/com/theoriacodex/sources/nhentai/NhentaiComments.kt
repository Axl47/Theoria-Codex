package com.theoriacodex.sources.nhentai

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.elementsOrEmpty
import com.theoriacodex.sources.common.intValue
import com.theoriacodex.sources.common.longValue
import com.theoriacodex.sources.common.optionalJsonArray
import com.theoriacodex.sources.common.optionalJsonObject
import com.theoriacodex.sources.common.stringValue

/** Comments are requested 50 to a page, the largest size the API accepts. */
internal const val NHENTAI_COMMENTS_PER_PAGE = 50

/**
 * Maps one `/galleries/{id}/comments` response. Pages are numbered from 1; [pageIndex] is the page
 * that was requested, so the next token exists only while `num_pages` says more follow.
 */
internal fun parseNhentaiCommentPage(root: JsonObject, pageIndex: Int): CommentPage {
    val comments = root.optionalJsonArray("result").elementsOrEmpty().mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseNhentaiComment)
    }
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
        // Avatar paths are relative to the image host, which serves them at its root.
        authorAvatarUrl = poster?.stringValue("avatar_url")?.trim()?.takeIf(String::isNotBlank)
            ?.let { "$NHENTAI_IMAGES_BASE/${it.trimStart('/')}" },
        body = raw.stringValue("body").orEmpty().trim(),
        createdAtEpochMs = raw.longValue("post_date")?.times(MS_PER_SECOND),
        // The profile page is a site account, not a searchable tag, so there is no uploads query.
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
